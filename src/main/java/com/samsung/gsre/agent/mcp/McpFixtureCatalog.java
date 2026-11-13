package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 오프라인 결정적 MCP 응답 픽스처.
 *
 * <p>데모·테스트에서 외부 K8s/Jira/관제 서버가 없어도 <b>같은 Raw 응답 → 같은 Reshaping 결과</b>를
 * 재현한다. 난수·현재시각을 쓰지 않으므로 로그 비교가 가능하다.</p>
 *
 * <p>운영 모드(HTTP)에서는 이 클래스가 호출되지 않는다.</p>
 */
@Component
public class McpFixtureCatalog {

    private final ObjectMapper mapper;

    public McpFixtureCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String rawResponse(McpApiMaster api) {
        return switch (api.apiId()) {
            case "MCP-K8S-001" -> wrap(K8S_POD_LIST);
            case "MCP-JIRA-001" -> wrap(JIRA_ACK);
            case "MCP-ALERT-001" -> wrap(ALERT_ACK);
            default -> wrap(genericAck(api));
        };
    }

    /** JSON-RPC 2.0 tools/call 응답 형태로 감싼다. 실제 MCP 서버 응답과 같은 모양이다. */
    private String wrap(String toolPayloadJson) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "offline-fixture-1");
        ObjectNode result = root.putObject("result");
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", toolPayloadJson);
        result.put("isError", false);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}";
        }
    }

    private String genericAck(McpApiMaster api) {
        return "{\"ok\":true,\"apiId\":\"" + api.apiId() + "\",\"tool\":\"" + api.toolName()
                + "\",\"mode\":\"OFFLINE_FIXTURE\"}";
    }

    /**
     * K8s PodList Raw 응답. 실제 API 처럼 managedFields/annotations/conditions 등 쓸모없는 필드가
     * 잔뜩 붙어 있다. Reshaping 룰이 이 중 6개 필드만 남기고, Succeeded Pod 는 버린다.
     */
    private static final String K8S_POD_LIST = """
            {
              "kind": "PodList",
              "apiVersion": "v1",
              "metadata": {
                "resourceVersion": "48291377",
                "remainingItemCount": 0,
                "selfLink": "/api/v1/namespaces/svcm-portal/pods"
              },
              "items": [
                {
                  "metadata": {
                    "name": "svcm-portal-7d9f8c6b5-2xk4p",
                    "namespace": "svcm-portal",
                    "uid": "5f0c2a44-8f3e-4f0b-9d1e-7c2b1a9e0001",
                    "resourceVersion": "48291001",
                    "creationTimestamp": "2026-11-12T23:41:07Z",
                    "labels": {"app": "svcm-portal", "pod-template-hash": "7d9f8c6b5", "tier": "web"},
                    "annotations": {
                      "kubectl.kubernetes.io/restartedAt": "2026-11-12T23:41:07Z",
                      "prometheus.io/scrape": "true",
                      "prometheus.io/port": "9102"
                    },
                    "managedFields": [
                      {"manager": "kube-controller-manager", "operation": "Update", "apiVersion": "v1",
                       "time": "2026-11-12T23:41:07Z", "fieldsType": "FieldsV1",
                       "fieldsV1": {"f:metadata": {"f:labels": {"f:app": {}}}, "f:spec": {"f:containers": {}}}}
                    ]
                  },
                  "spec": {
                    "nodeName": "macmini-node-01",
                    "restartPolicy": "Always",
                    "dnsPolicy": "ClusterFirst",
                    "serviceAccountName": "svcm-portal",
                    "containers": [
                      {"name": "portal", "image": "registry.local/svcm/portal:2.5.1",
                       "ports": [{"containerPort": 8080, "protocol": "TCP"}],
                       "resources": {"limits": {"cpu": "2", "memory": "2Gi"}}},
                      {"name": "envoy", "image": "registry.local/svcm/envoy:1.29.2",
                       "resources": {"limits": {"cpu": "500m", "memory": "512Mi"}}}
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "hostIP": "192.168.0.11",
                    "podIP": "10.244.1.35",
                    "startTime": "2026-11-12T23:41:12Z",
                    "conditions": [
                      {"type": "Ready", "status": "True", "lastTransitionTime": "2026-11-12T23:41:40Z"},
                      {"type": "ContainersReady", "status": "True", "lastTransitionTime": "2026-11-12T23:41:40Z"}
                    ],
                    "containerStatuses": [
                      {"name": "portal", "ready": true, "restartCount": 2, "image": "registry.local/svcm/portal:2.5.1",
                       "state": {"running": {"startedAt": "2026-11-13T19:02:11Z"}}},
                      {"name": "envoy", "ready": true, "restartCount": 1, "image": "registry.local/svcm/envoy:1.29.2",
                       "state": {"running": {"startedAt": "2026-11-13T19:02:11Z"}}}
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "svcm-portal-7d9f8c6b5-9m2vq",
                    "namespace": "svcm-portal",
                    "uid": "5f0c2a44-8f3e-4f0b-9d1e-7c2b1a9e0002",
                    "resourceVersion": "48291044",
                    "creationTimestamp": "2026-11-13T18:55:31Z",
                    "labels": {"app": "svcm-portal", "pod-template-hash": "7d9f8c6b5", "tier": "web"},
                    "annotations": {"prometheus.io/scrape": "true"},
                    "managedFields": [
                      {"manager": "kubelet", "operation": "Update", "apiVersion": "v1",
                       "time": "2026-11-13T19:01:02Z", "fieldsType": "FieldsV1"}
                    ]
                  },
                  "spec": {
                    "nodeName": "macmini-node-02",
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "portal", "image": "registry.local/svcm/portal:2.5.1",
                       "ports": [{"containerPort": 8080, "protocol": "TCP"}]}
                    ]
                  },
                  "status": {
                    "phase": "Failed",
                    "hostIP": "192.168.0.12",
                    "podIP": "10.244.2.71",
                    "startTime": "2026-11-13T18:55:33Z",
                    "conditions": [
                      {"type": "Ready", "status": "False", "reason": "ContainersNotReady",
                       "message": "containers with unready status: [portal]"}
                    ],
                    "containerStatuses": [
                      {"name": "portal", "ready": false, "restartCount": 7, "image": "registry.local/svcm/portal:2.5.1",
                       "state": {"terminated": {"exitCode": 137, "reason": "OOMKilled",
                                                "startedAt": "2026-11-13T19:00:41Z", "finishedAt": "2026-11-13T19:01:02Z"}},
                       "lastState": {"terminated": {"exitCode": 137, "reason": "OOMKilled"}}}
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "svcm-portal-7d9f8c6b5-kd8zt",
                    "namespace": "svcm-portal",
                    "uid": "5f0c2a44-8f3e-4f0b-9d1e-7c2b1a9e0003",
                    "resourceVersion": "48291050",
                    "creationTimestamp": "2026-11-13T19:01:20Z",
                    "labels": {"app": "svcm-portal", "pod-template-hash": "7d9f8c6b5"},
                    "managedFields": []
                  },
                  "spec": {
                    "restartPolicy": "Always",
                    "containers": [
                      {"name": "portal", "image": "registry.local/svcm/portal:2.5.1",
                       "resources": {"requests": {"cpu": "1", "memory": "1Gi"}}}
                    ]
                  },
                  "status": {
                    "phase": "Pending",
                    "conditions": [
                      {"type": "PodScheduled", "status": "False", "reason": "Unschedulable",
                       "message": "0/3 nodes are available: 3 Insufficient cpu."}
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "nightly-report-28934410-8c7pl",
                    "namespace": "svcm-portal",
                    "uid": "5f0c2a44-8f3e-4f0b-9d1e-7c2b1a9e0004",
                    "resourceVersion": "48290099",
                    "creationTimestamp": "2026-11-13T15:00:00Z",
                    "labels": {"job-name": "nightly-report-28934410"},
                    "managedFields": []
                  },
                  "spec": {
                    "nodeName": "macmini-node-03",
                    "restartPolicy": "Never",
                    "containers": [{"name": "report", "image": "registry.local/svcm/report:1.8.0"}]
                  },
                  "status": {
                    "phase": "Succeeded",
                    "containerStatuses": [
                      {"name": "report", "ready": false, "restartCount": 0,
                       "state": {"terminated": {"exitCode": 0, "reason": "Completed"}}}
                    ]
                  }
                }
              ]
            }
            """;

    /** Jira MCP 서버의 접수 응답. 작업이력 Raw 는 여기서 만들지 않는다(내부 DB 가 원천). */
    private static final String JIRA_ACK = """
            {"synced": 3, "status": "ACCEPTED", "receiptId": "JIRA-SYNC-260113-0001",
             "server": "jira-mcp-fixture", "processedAt": "2026-11-13T20:00:00Z"}
            """;

    /** 관제 알람 MCP 서버의 접수 응답. */
    private static final String ALERT_ACK = """
            {"accepted": 2, "channel": "#sre-alerts", "messageId": "ALT-260113-7781",
             "server": "monitor-mcp-fixture"}
            """;
}
