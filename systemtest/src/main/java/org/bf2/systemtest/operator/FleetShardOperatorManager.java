package org.bf2.systemtest.operator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.test.Environment;
import org.bf2.test.TestUtils;
import org.bf2.test.executor.ExecBuilder;
import org.bf2.test.k8s.KubeClient;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;

public class FleetShardOperatorManager {
    private static final Logger LOGGER = LogManager.getLogger(FleetShardOperatorManager.class);
    public static final String OPERATOR_NS = "kas-fleetshard";
    public static final String OPERATOR_NAME = "kas-fleetshard-operator";
    public static final String SYNC_NAME = "kas-fleetshard-sync";
    private static List<HasMetadata> installedCrds;

    public static void deployFleetShardOperator(KubeClient kubeClient) throws Exception {
        LOGGER.info("Installing {}", OPERATOR_NAME);
        LOGGER.info("Installing CRD");
        installedCrds = kubeClient.client().load(new FileInputStream(Environment.CRD_PATH.toString())).get();
        installedCrds.forEach(crd ->
                kubeClient.client().apiextensions().v1beta1().customResourceDefinitions().createOrReplace((CustomResourceDefinition) crd));

        kubeClient.client().namespaces().createOrReplace(new NamespaceBuilder().withNewMetadata().withName(OPERATOR_NS).endMetadata().build());
        LOGGER.info("Installing operator from files: {}", Environment.YAML_OPERATOR_BUNDLE_PATH.toString());
        kubeClient.apply(OPERATOR_NS, TestUtils.replacer(Map.of("##IMAGE##", Environment.FLEET_SHARD_IMAGE)), Environment.YAML_OPERATOR_BUNDLE_PATH);

        TestUtils.waitFor("Operator ready", 1_000, 120_000, FleetShardOperatorManager::isOperatorInstalled);
        LOGGER.info("Fleetshard operator is deployed");
    }

    public static void deployFleetShardSync(KubeClient kubeClient) throws Exception {
        LOGGER.info("Installing {}", SYNC_NAME);
        kubeClient.apply(OPERATOR_NS, in -> in, Environment.YAML_SYNC_BUNDLE_PATH);

        TestUtils.waitFor("Sync ready", 1_000, 120_000, FleetShardOperatorManager::isSyncInstalled);
        LOGGER.info("Fleetshard sync is deployed");
    }

    public static boolean isOperatorInstalled() {
        return KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains(OPERATOR_NAME)) &&
                TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains(OPERATOR_NAME)).findFirst().get());
    }

    public static boolean isSyncInstalled() {
        return KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                .list().getItems().stream().anyMatch(pod -> pod.getMetadata().getName().contains(SYNC_NAME)) &&
                TestUtils.isPodReady(KubeClient.getInstance().client().pods().inNamespace(OPERATOR_NS)
                        .list().getItems().stream().filter(pod ->
                                pod.getMetadata().getName().contains(SYNC_NAME)).findFirst().get());
    }

    public static String createEndpoint(KubeClient kubeClient) {
        String externalEndpointName = SYNC_NAME + "-external";
        if (kubeClient.isGenericKubernetes()) {
            if (kubeClient.client().services().inNamespace(OPERATOR_NS).list().getItems().stream().anyMatch(service -> service.getMetadata().getName().equals(externalEndpointName))) {
                kubeClient.client().services().inNamespace(OPERATOR_NS).withName(externalEndpointName).delete();
            }
            kubeClient.cmdClient().namespace(OPERATOR_NS).execInCurrentNamespace("expose", "service", SYNC_NAME, "--type=LoadBalancer", "--name", externalEndpointName);
            return new ExecBuilder()
                    .withCommand("minikube", "service", "--url", externalEndpointName, "-n", OPERATOR_NS)
                    .logToOutput(false)
                    .exec().out().trim();
        } else {
            OpenShiftClient openShiftClient = kubeClient.client().adapt(OpenShiftClient.class);
            if (openShiftClient.routes().inNamespace(OPERATOR_NS).list().getItems().stream().anyMatch(service -> service.getMetadata().getName().equals(externalEndpointName))) {
                openShiftClient.routes().inNamespace(OPERATOR_NS).withName(externalEndpointName).delete();
            }
            kubeClient.cmdClient().namespace(OPERATOR_NS).execInCurrentNamespace("expose", "service", SYNC_NAME, "--name", externalEndpointName);
            Route r = openShiftClient.routes().inNamespace(OPERATOR_NS).withName(externalEndpointName).get();
            return String.format("%s://%s:%d", r.getSpec().getPort().getTargetPort().getStrVal(),
                    r.getSpec().getHost(),
                    r.getSpec().getPort().getTargetPort().getStrVal().equals("http") ? 80 : 443);
        }
    }

    public static void deleteFleetShard(KubeClient kubeClient) throws InterruptedException {
        LOGGER.info("Deleting managedkafkas and kas-fleetshard");
        var mkCli = kubeClient.client().customResources(ManagedKafka.class);
        mkCli.inAnyNamespace().list().getItems().forEach(mk -> mkCli.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).delete());
        Thread.sleep(10_000);
        installedCrds.forEach(crd -> kubeClient.client().apiextensions().v1beta1().customResourceDefinitions().withName(crd.getMetadata().getName()).delete());
        LOGGER.info("Crds deleted");
        kubeClient.client().namespaces().withName(OPERATOR_NS).delete();
        TestUtils.waitFor("Operator ns deleted", 2_000, 120_000, () -> !kubeClient.namespaceExists(OPERATOR_NS));
        LOGGER.info("kas-fleetshard is deleted");
    }
}
