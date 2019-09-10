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
package org.apache.spark.scheduler.cluster.k8s

import java.io.File

import io.fabric8.kubernetes.client.KubernetesClient

import org.apache.spark.SparkConf
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.features._
import org.apache.spark.deploy.k8s.features.{BasicExecutorFeatureStep, EnvSecretsFeatureStep, LocalDirsFeatureStep, MountSecretsFeatureStep}

private[spark] class KubernetesExecutorBuilder(
    provideBasicStep: (KubernetesConf [KubernetesExecutorSpecificConf])
      => BasicExecutorFeatureStep =
      new BasicExecutorFeatureStep(_),
    provideSecretsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf])
      => MountSecretsFeatureStep =
      new MountSecretsFeatureStep(_),
    provideEnvSecretsStep:
      (KubernetesConf[_ <: KubernetesRoleSpecificConf] => EnvSecretsFeatureStep) =
      new EnvSecretsFeatureStep(_),
    provideLocalDirsStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf])
      => LocalDirsFeatureStep =
      new LocalDirsFeatureStep(_),
    provideVolumesStep: (KubernetesConf[_ <: KubernetesRoleSpecificConf]
      => MountVolumesFeatureStep) =
      new MountVolumesFeatureStep(_),
    provideHadoopConfStep: (
      KubernetesConf[KubernetesExecutorSpecificConf]
      => HadoopConfExecutorFeatureStep) =
      new HadoopConfExecutorFeatureStep(_),
    provideKerberosConfStep: (
      KubernetesConf[KubernetesExecutorSpecificConf]
      => KerberosConfExecutorFeatureStep) =
      new KerberosConfExecutorFeatureStep(_),
    provideHadoopSparkUserStep: (
      KubernetesConf[KubernetesExecutorSpecificConf]
      => HadoopSparkUserExecutorFeatureStep) =
      new HadoopSparkUserExecutorFeatureStep(_),
    provideInitialPod: () => SparkPod = SparkPod.initialPod) {

  def buildFromFeatures(
    kubernetesConf: KubernetesConf[KubernetesExecutorSpecificConf]): SparkPod = {

    val baseFeatures = Seq(provideBasicStep(kubernetesConf), provideLocalDirsStep(kubernetesConf))
    val secretFeature = if (kubernetesConf.roleSecretNamesToMountPaths.nonEmpty) {
      Seq(provideSecretsStep(kubernetesConf))
    } else Nil
    val secretEnvFeature = if (kubernetesConf.roleSecretEnvNamesToKeyRefs.nonEmpty) {
      Seq(provideEnvSecretsStep(kubernetesConf))
    } else Nil
    val volumesFeature = if (kubernetesConf.roleVolumes.nonEmpty) {
      Seq(provideVolumesStep(kubernetesConf))
    } else Nil

    val allFeatures = baseFeatures ++ secretFeature ++ secretEnvFeature ++ volumesFeature

    var executorPod = provideInitialPod()
    for (feature <- allFeatures) {
      executorPod = feature.configurePod(executorPod)
    }
    executorPod
  }
}

private[spark] object KubernetesExecutorBuilder {
  def apply(kubernetesClient: KubernetesClient, conf: SparkConf): KubernetesExecutorBuilder = {
    conf.get(Config.KUBERNETES_EXECUTOR_PODTEMPLATE_FILE)
      .map(new File(_))
      .map(file => new KubernetesExecutorBuilder(provideInitialPod = () =>
          KubernetesUtils.loadPodFromTemplate(
            kubernetesClient,
            file,
            conf.get(Config.KUBERNETES_EXECUTOR_PODTEMPLATE_CONTAINER_NAME))
      ))
      .getOrElse(new KubernetesExecutorBuilder())
  }
}
