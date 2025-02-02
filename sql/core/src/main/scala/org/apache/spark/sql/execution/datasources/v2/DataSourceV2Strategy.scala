/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.{AnalysisException, Strategy}
import org.apache.spark.sql.catalog.v2.StagingTableCatalog
import org.apache.spark.sql.catalyst.expressions.{And, AttributeReference, AttributeSet, Expression, PredicateHelper, SubqueryExpression}
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{AlterTable, AppendData, CreateTableAsSelect, CreateV2Table, DeleteFromTable, DescribeTable, DropTable, LogicalPlan, OverwriteByExpression, OverwritePartitionsDynamic, Repartition, ReplaceTable, ReplaceTableAsSelect, ShowTables}
import org.apache.spark.sql.execution.{FilterExec, ProjectExec, SparkPlan}
import org.apache.spark.sql.execution.datasources.DataSourceStrategy
import org.apache.spark.sql.execution.streaming.continuous.{ContinuousCoalesceExec, WriteToContinuousDataSource, WriteToContinuousDataSourceExec}
import org.apache.spark.sql.sources
import org.apache.spark.sql.sources.v2.TableCapability
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.reader.streaming.{ContinuousStream, MicroBatchStream}
import org.apache.spark.sql.sources.v2.writer.V1WriteBuilder
import org.apache.spark.sql.util.CaseInsensitiveStringMap

object DataSourceV2Strategy extends Strategy with PredicateHelper {

  /**
   * Pushes down filters to the data source reader
   *
   * @return pushed filter and post-scan filters.
   */
  private def pushFilters(
      scanBuilder: ScanBuilder,
      filters: Seq[Expression]): (Seq[Expression], Seq[Expression]) = {
    scanBuilder match {
      case r: SupportsPushDownFilters =>
        // A map from translated data source leaf node filters to original catalyst filter
        // expressions. For a `And`/`Or` predicate, it is possible that the predicate is partially
        // pushed down. This map can be used to construct a catalyst filter expression from the
        // input filter, or a superset(partial push down filter) of the input filter.
        val translatedFilterToExpr = mutable.HashMap.empty[sources.Filter, Expression]
        val translatedFilters = mutable.ArrayBuffer.empty[sources.Filter]
        // Catalyst filter expression that can't be translated to data source filters.
        val untranslatableExprs = mutable.ArrayBuffer.empty[Expression]

        for (filterExpr <- filters) {
          val translated =
            DataSourceStrategy.translateFilterWithMapping(filterExpr, Some(translatedFilterToExpr))
          if (translated.isEmpty) {
            untranslatableExprs += filterExpr
          } else {
            translatedFilters += translated.get
          }
        }

        // Data source filters that need to be evaluated again after scanning. which means
        // the data source cannot guarantee the rows returned can pass these filters.
        // As a result we must return it so Spark can plan an extra filter operator.
        val postScanFilters = r.pushFilters(translatedFilters.toArray).map { filter =>
          DataSourceStrategy.rebuildExpressionFromFilter(filter, translatedFilterToExpr)
        }
        // The filters which are marked as pushed to this data source
        val pushedFilters = r.pushedFilters().map { filter =>
          DataSourceStrategy.rebuildExpressionFromFilter(filter, translatedFilterToExpr)
        }
        (pushedFilters, untranslatableExprs ++ postScanFilters)

      case _ => (Nil, filters)
    }
  }

  /**
   * Applies column pruning to the data source, w.r.t. the references of the given expressions.
   *
   * @return the created `ScanConfig`(since column pruning is the last step of operator pushdown),
   *         and new output attributes after column pruning.
   */
  // TODO: nested column pruning.
  private def pruneColumns(
      scanBuilder: ScanBuilder,
      relation: DataSourceV2Relation,
      exprs: Seq[Expression]): (Scan, Seq[AttributeReference]) = {
    scanBuilder match {
      case r: SupportsPushDownRequiredColumns =>
        val requiredColumns = AttributeSet(exprs.flatMap(_.references))
        val neededOutput = relation.output.filter(requiredColumns.contains)
        if (neededOutput != relation.output) {
          r.pruneColumns(neededOutput.toStructType)
          val scan = r.build()
          val nameToAttr = relation.output.map(_.name).zip(relation.output).toMap
          scan -> scan.readSchema().toAttributes.map {
            // We have to keep the attribute id during transformation.
            a => a.withExprId(nameToAttr(a.name).exprId)
          }
        } else {
          r.build() -> relation.output
        }

      case _ => scanBuilder.build() -> relation.output
    }
  }

  import DataSourceV2Implicits._

  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case PhysicalOperation(project, filters, relation: DataSourceV2Relation) =>
      val scanBuilder = relation.newScanBuilder()

      val (withSubquery, withoutSubquery) = filters.partition(SubqueryExpression.hasSubquery)
      val normalizedFilters = DataSourceStrategy.normalizeFilters(
        withoutSubquery, relation.output)

      // `pushedFilters` will be pushed down and evaluated in the underlying data sources.
      // `postScanFilters` need to be evaluated after the scan.
      // `postScanFilters` and `pushedFilters` can overlap, e.g. the parquet row group filter.
      val (pushedFilters, postScanFiltersWithoutSubquery) =
        pushFilters(scanBuilder, normalizedFilters)
      val postScanFilters = postScanFiltersWithoutSubquery ++ withSubquery
      val (scan, output) = pruneColumns(scanBuilder, relation, project ++ postScanFilters)
      logInfo(
        s"""
           |Pushing operators to ${relation.name}
           |Pushed Filters: ${pushedFilters.mkString(", ")}
           |Post-Scan Filters: ${postScanFilters.mkString(",")}
           |Output: ${output.mkString(", ")}
         """.stripMargin)

      val batchExec = BatchScanExec(output, scan)

      val filterCondition = postScanFilters.reduceLeftOption(And)
      val withFilter = filterCondition.map(FilterExec(_, batchExec)).getOrElse(batchExec)

      val withProjection = if (withFilter.output != project || !batchExec.supportsColumnar) {
        ProjectExec(project, withFilter)
      } else {
        withFilter
      }

      withProjection :: Nil

    case r: StreamingDataSourceV2Relation if r.startOffset.isDefined && r.endOffset.isDefined =>
      val microBatchStream = r.stream.asInstanceOf[MicroBatchStream]
      val scanExec = MicroBatchScanExec(
        r.output, r.scan, microBatchStream, r.startOffset.get, r.endOffset.get)

      val withProjection = if (scanExec.supportsColumnar) {
        scanExec
      } else {
        // Add a Project here to make sure we produce unsafe rows.
        ProjectExec(r.output, scanExec)
      }

      withProjection :: Nil

    case r: StreamingDataSourceV2Relation if r.startOffset.isDefined && r.endOffset.isEmpty =>
      val continuousStream = r.stream.asInstanceOf[ContinuousStream]
      val scanExec = ContinuousScanExec(r.output, r.scan, continuousStream, r.startOffset.get)

      val withProjection = if (scanExec.supportsColumnar) {
        scanExec
      } else {
        // Add a Project here to make sure we produce unsafe rows.
        ProjectExec(r.output, scanExec)
      }

      withProjection :: Nil

    case WriteToDataSourceV2(writer, query) =>
      WriteToDataSourceV2Exec(writer, planLater(query)) :: Nil

    case CreateV2Table(catalog, ident, schema, parts, props, ifNotExists) =>
      CreateTableExec(catalog, ident, schema, parts, props, ifNotExists) :: Nil

    case CreateTableAsSelect(catalog, ident, parts, query, props, options, ifNotExists) =>
      val writeOptions = new CaseInsensitiveStringMap(options.asJava)
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicCreateTableAsSelectExec(
            staging, ident, parts, query, planLater(query), props, writeOptions, ifNotExists) :: Nil
        case _ =>
          CreateTableAsSelectExec(
            catalog, ident, parts, query, planLater(query), props, writeOptions, ifNotExists) :: Nil
      }

    case ReplaceTable(catalog, ident, schema, parts, props, orCreate) =>
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicReplaceTableExec(staging, ident, schema, parts, props, orCreate = orCreate) :: Nil
        case _ =>
          ReplaceTableExec(catalog, ident, schema, parts, props, orCreate = orCreate) :: Nil
      }

    case ReplaceTableAsSelect(catalog, ident, parts, query, props, options, orCreate) =>
      val writeOptions = new CaseInsensitiveStringMap(options.asJava)
      catalog match {
        case staging: StagingTableCatalog =>
          AtomicReplaceTableAsSelectExec(
            staging,
            ident,
            parts,
            query,
            planLater(query),
            props,
            writeOptions,
            orCreate = orCreate) :: Nil
        case _ =>
          ReplaceTableAsSelectExec(
            catalog,
            ident,
            parts,
            query,
            planLater(query),
            props,
            writeOptions,
            orCreate = orCreate) :: Nil
      }

    case AppendData(r: DataSourceV2Relation, query, _) =>
      r.table.asWritable match {
        case v1 if v1.supports(TableCapability.V1_BATCH_WRITE) =>
          AppendDataExecV1(v1, r.options, query) :: Nil
        case v2 =>
          AppendDataExec(v2, r.options, planLater(query)) :: Nil
      }

    case OverwriteByExpression(r: DataSourceV2Relation, deleteExpr, query, _) =>
      // fail if any filter cannot be converted. correctness depends on removing all matching data.
      val filters = splitConjunctivePredicates(deleteExpr).map {
        filter => DataSourceStrategy.translateFilter(deleteExpr).getOrElse(
          throw new AnalysisException(s"Cannot translate expression to source filter: $filter"))
      }.toArray
      r.table.asWritable match {
        case v1 if v1.supports(TableCapability.V1_BATCH_WRITE) =>
          OverwriteByExpressionExecV1(v1, filters, r.options, query) :: Nil
        case v2 =>
          OverwriteByExpressionExec(v2, filters, r.options, planLater(query)) :: Nil
      }

    case OverwritePartitionsDynamic(r: DataSourceV2Relation, query, _) =>
      OverwritePartitionsDynamicExec(r.table.asWritable, r.options, planLater(query)) :: Nil

    case DeleteFromTable(r: DataSourceV2Relation, condition) =>
      if (SubqueryExpression.hasSubquery(condition)) {
        throw new AnalysisException(
          s"Delete by condition with subquery is not supported: $condition")
      }
      // fail if any filter cannot be converted. correctness depends on removing all matching data.
      val filters = splitConjunctivePredicates(condition).map {
        f => DataSourceStrategy.translateFilter(f).getOrElse(
          throw new AnalysisException(s"Exec delete failed:" +
              s" cannot translate expression to source filter: $f"))
      }.toArray
      DeleteFromTableExec(r.table.asDeletable, filters) :: Nil

    case WriteToContinuousDataSource(writer, query) =>
      WriteToContinuousDataSourceExec(writer, planLater(query)) :: Nil

    case Repartition(1, false, child) =>
      val isContinuous = child.find {
        case r: StreamingDataSourceV2Relation => r.stream.isInstanceOf[ContinuousStream]
        case _ => false
      }.isDefined

      if (isContinuous) {
        ContinuousCoalesceExec(1, planLater(child)) :: Nil
      } else {
        Nil
      }

    case desc @ DescribeTable(r: DataSourceV2Relation, isExtended) =>
      DescribeTableExec(desc.output, r.table, isExtended) :: Nil

    case DropTable(catalog, ident, ifExists) =>
      DropTableExec(catalog, ident, ifExists) :: Nil

    case AlterTable(catalog, ident, _, changes) =>
      AlterTableExec(catalog, ident, changes) :: Nil

    case r : ShowTables =>
      ShowTablesExec(r.output, r.catalog, r.namespace, r.pattern) :: Nil

    case _ => Nil
  }
}
