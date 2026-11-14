package com.samsung.gsre.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 오프라인 결정적 MCP/REST 응답 픽스처.
 *
 * <p>데모·테스트에서 외부 K8s/Jira/관제/CMDB 서버가 없어도 <b>같은 Raw 응답 → 같은 Reshaping 결과</b>를
 * 재현한다. 난수·현재시각을 쓰지 않으므로 로그 비교가 가능하다.</p>
 *
 * <p>MCP 도구는 JSON-RPC 2.0 봉투로 감싸고, REST API 는 봉투 없이 순수 JSON 본문을 돌려준다.
 * (게이트웨이 두 개의 차이가 픽스처에도 그대로 드러나야 감사 로그 비교가 쉽다.)</p>
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
            case "REST-CMDB-001" -> CMDB_APP_DETAIL;
            case "REST-INCIDENT-001" -> INCIDENT_SYNC_ACK;
            case "REST-NOTIFY-001" -> WEBHOOK_ACK;
            default -> api.isRestTransport() ? restGenericAck(api) : wrap(genericAck(api));
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

    /** REST 게이트웨이는 JSON-RPC 봉투를 벗기지 않으므로 봉투 없는 JSON 을 그대로 준다. */
    private String restGenericAck(McpApiMaster api) {
        return genericAck(api);
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

    /**
     * CMDB REST API 의 앱 상세 Raw 응답. 실제 CMDB 처럼 owners/deps/metrics/auditLog/config 등
     * 쓸모없는 필드가 잔뜩 붙어 있고, Reshaping 룰이 이 중 11개만 남긴다.
     * (오프라인 픽스처는 결정적이므로 appId 기본값 APP-1001 기준이다.)
     */
    private static final String CMDB_APP_DETAIL = """
            {
              "requestId": "cmdb-7f3c19a2",
              "resultCode": "OK",
              "serverTime": "2026-11-14T09:12:41+09:00",
              "data": {
                "appId": "APP-1001",
                "appName": "서비스맵포털",
                "appType": "WEB",
                "serviceId": "SVC-01",
                "serviceName": "서비스맵포털",
                "domainName": "svcm.samsung.com",
                "tier": "TIER-1",
                "criticality": "CRITICAL",
                "status": "OPERATING",
                "environment": "PROD",
                "owners": [
                  {"userId": "U1001", "name": "김민수", "deptName": "SRE플랫폼팀",
                   "email": "kim.minsu@samsung.com", "phone": "010-1234-5678", "role": "OWNER"},
                  {"userId": "U1005", "name": "한도윤", "deptName": "SRE플랫폼팀",
                   "email": "han.doyun@samsung.com", "phone": "010-5678-9012", "role": "OPERATOR"}
                ],
                "metrics": {
                  "openIncidents": 1,
                  "restartCount": 9,
                  "cpuUsage": 41.7,
                  "memUsage": 68.2,
                  "p95LatencyMs": 312
                },
                "dependencies": [
                  {"appId": "APP-1002", "type": "HTTP", "direction": "OUT", "timeoutMs": 3000},
                  {"appId": "APP-1005", "type": "KAFKA", "topic": "svcm.portal.events"}
                ],
                "config": {
                  "replicas": 3,
                  "image": "registry.local/svcm/portal:2.5.1",
                  "ingressHost": "svcm.samsung.com",
                  "env": [
                    {"key": "SPRING_PROFILES_ACTIVE", "value": "prod"},
                    {"key": "JAVA_OPTS", "value": "-Xms2g -Xmx2g"}
                  ]
                },
                "tags": ["portal", "svcm", "tier1", "sre"],
                "auditLog": [
                  {"at": "2026-11-13T18:22:10+09:00", "by": "U1005", "action": "CONFIG_CHANGE",
                   "detail": "replicas 2 -> 3"},
                  {"at": "2026-11-12T23:41:07+09:00", "by": "U1001", "action": "DEPLOY",
                   "detail": "portal 2.5.0 -> 2.5.1"}
                ]
              },
              "paging": {"page": 1, "size": 1, "total": 1}
            }
            """;

    /** 장애 이력 동기화 REST API 의 배치 접수 응답. */
    private static final String INCIDENT_SYNC_ACK = """
            {"syncedCount": 5, "batchId": "INC-BATCH-261114-0001", "status": "ACCEPTED",
             "processedAt": "2026-11-14T09:12:41+09:00", "server": "incident-rest-fixture"}
            """;

    /** SRE 알람 Webhook 의 접수 응답. */
    private static final String WEBHOOK_ACK = """
            {"notified": 2, "channel": "#sre-alerts", "status": "SENT",
             "receiptId": "WH-261114-4412", "server": "webhook-rest-fixture"}
            """;
}
