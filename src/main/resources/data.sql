-- =====================================================================
-- G-SRE 장애 지원 AI Agent - MCP API 확장 데모 시드
--   * 코드 재배포 없이 테이블 설정만으로 MCP 도구를 늘릴 수 있음을 보여주는 3건. (MCP/HTTP)
--   * 재기동해도 중복되지 않도록 '없을 때만 INSERT' 형태로 쓴다.
--     (운영 MySQL 에서도 동일 문법이 동작한다. MERGE/ON DUPLICATE 는 쓰지 않는다.)
--   * 룰 본문은 선언적 JSON 이며 DataReshaper 가 해석한다. (docs/architecture.md §8)
-- =====================================================================

-- 1) [OUTBOUND] K8s Pod 목록: 방대한 Raw JSON 응답에서 장애 판단에 필요한 필드만 추출
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, auth_type, auth_config,
     request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'MCP-K8S-001', 'K8s Pod 상태 조회', 'k8s.pod.list', 'OUTBOUND', 'HTTP',
       'http://localhost:18080/mcp/k8s', 'BEARER', '{"header":"Authorization","envVar":"MCP_K8S_TOKEN"}',
       '{"type":"object","properties":{"namespace":{"type":"string"},"labelSelector":{"type":"string"},"limit":{"type":"integer"}}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"namespace","source":"$.namespace","default":"default"},{"target":"labelSelector","source":"$.labelSelector","default":"app=svcm-portal"},{"target":"limit","source":"$.limit","transform":"TO_INT","default":20}]}',
       '{"version":"1.0","mode":"COLLECTION","itemPath":"$.items[*]","limit":10,"dropNull":true,"filter":[{"path":"$.status.phase","op":"NE","value":"Succeeded"}],"fields":[{"target":"podName","source":"$.metadata.name","required":true},{"target":"namespace","source":"$.metadata.namespace","default":"default"},{"target":"phase","source":"$.status.phase","default":"Unknown"},{"target":"node","source":"$.spec.nodeName","default":"unassigned"},{"target":"restarts","source":"$.status.containerStatuses[*].restartCount","aggregate":"SUM","default":0},{"target":"image","source":"$.spec.containers[0].image","transform":"TO_STRING"}]}',
       NULL, 3000, 'Y',
       'K8s API 의 수 KB Raw 응답을 Pod 단위 6개 필드로 압축한다. 정상 종료(Succeeded) Pod 는 버린다.', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'MCP-K8S-001');

-- 2) [INBOUND] Jira 작업이력 동기화: 내부 뷰 로우데이터를 MCP 도구 규격(tools/call)으로 변환해 발송
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, auth_type, auth_config,
     request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'MCP-JIRA-001', 'Jira 작업이력 동기화', 'jira.worklog.sync', 'INBOUND', 'HTTP',
       'http://localhost:18080/mcp/jira', 'API_KEY', '{"header":"X-Api-Key","envVar":"MCP_JIRA_API_KEY"}',
       '{"type":"object","properties":{"summaryOnly":{"type":"boolean"}}}',
       '{"version":"1.0","mode":"COLLECTION","itemPath":"$[*]","limit":50,"fields":[{"target":"issueKey","source":"$.issue_key","required":true},{"target":"projectKey","source":"$.project_key"},{"target":"appName","source":"$.app_name"},{"target":"assignee","source":"$.assignee_name"},{"target":"hours","source":"$.worklog_hours","transform":"TO_NUMBER","default":0},{"target":"workDate","source":"$.worklog_date","transform":"TO_STRING"},{"target":"summary","source":"$.summary","transform":"TRIM"}],"envelope":{"type":"MCP_TOOL_CALL","tool":"jira.worklog.sync","argumentsKey":"entries"}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"synced","source":"$.synced","default":0},{"target":"status","source":"$.status","default":"UNKNOWN"},{"target":"receiptId","source":"$.receiptId"}]}',
       'SELECT issue_key, project_key, app_id, app_name, assignee_name, issue_type, status, summary, worklog_hours, worklog_date FROM svcm_vw_jira_dri_worklog ORDER BY worklog_date DESC',
       5000, 'Y',
       'Jira DRI 작업이력 뷰를 읽어 MCP jira.worklog.sync 규격(entries[])으로 변환해 전송하고, 접수 결과만 남긴다.', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'MCP-JIRA-001');

-- 3) [BIDIRECTIONAL] 관제 알람 발송: 내부 자산 로우 → 알람 스펙 변환 발송, 응답에서 접수 결과 정제
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, auth_type, auth_config,
     request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'MCP-ALERT-001', '관제 알람 발송', 'monitor.alert.push', 'BIDIRECTIONAL', 'HTTP',
       'http://localhost:18080/mcp/alert', 'NONE', NULL,
       '{"type":"object","properties":{"channel":{"type":"string"}}}',
       '{"version":"1.0","mode":"COLLECTION","itemPath":"$[*]","fields":[{"target":"appId","source":"$.app_id","required":true},{"target":"appName","source":"$.app_name"},{"target":"severity","constant":"HIGH"},{"target":"status","source":"$.status","default":"UNKNOWN"}],"envelope":{"type":"MCP_TOOL_CALL","tool":"monitor.alert.push","argumentsKey":"alerts"}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"accepted","source":"$.accepted","default":0},{"target":"channel","source":"$.channel","default":"#sre-alerts"},{"target":"messageId","source":"$.messageId"}]}',
       'SELECT app_id, app_name, criticality, status FROM svcm_tb_app_master WHERE criticality = ''CRITICAL'' ORDER BY app_id',
       5000, 'Y',
       'CRITICAL 자산만 골라 알람 스펙으로 변환해 발송하고, 응답의 접수 건수/채널만 보관한다.', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'MCP-ALERT-001');

-- =====================================================================
-- 4~6) REST API 시드 (2026-11-14 추가)
--   * 위 3건은 MCP(JSON-RPC tools/call) 전용이다. 아래 3건은 같은 테이블·같은 룰 문법으로
--     '순수 REST API'(JSON-RPC 봉투 없음, HTTP 메서드/헤더/Query String 사용)를 구동한다.
--   * transport_type='REST' → RestApiClientGateway, 'HTTP'/'MCP'/'SSE' → McpClientGateway. (코드 분기 없음, 값 분기)
--   * source_query 는 schema.sql 의 실제 컬럼만 쓴다. (svcm_tb_app_master, svcm_tb_jira_issue, svcm_tb_user_info_master)
--   * 엔드포인트는 로컬 검증용 주소이며, 운영에서는 실제 REST 서버 주소로 바꾼다.
-- =====================================================================

-- 4) [OUTBOUND] CMDB 앱 상세 조회: GET + Path Variable({appId}) + Query Parameter,
--    방대한 CMDB JSON(data/owners/deps/metrics/auditLog ...)에서 핵심 11개 필드만 추출
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, http_method, headers_config,
     auth_type, auth_config, request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'REST-CMDB-001', 'CMDB 앱 상세 조회', 'cmdb.app.get', 'OUTBOUND', 'REST',
       'http://localhost:18080/api/cmdb/apps/{appId}', 'GET',
       '{"Accept":"application/json","X-GSRE-Source":"incident-agent-spring-ai"}',
       'NONE', NULL,
       '{"type":"object","properties":{"appId":{"type":"string"},"include":{"type":"string"},"depth":{"type":"integer"}}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"appId","source":"$.appId","default":"APP-1001"},{"target":"include","source":"$.include","default":"owners,deps,metrics"},{"target":"depth","source":"$.depth","transform":"TO_INT","default":2}]}',
       '{"version":"1.0","mode":"OBJECT","rootPath":"$.data","dropNull":true,"fields":[{"target":"appId","source":"$.appId","required":true},{"target":"appName","source":"$.appName"},{"target":"appType","source":"$.appType"},{"target":"serviceName","source":"$.serviceName"},{"target":"owner","source":"$.owners[0].name"},{"target":"ownerDept","source":"$.owners[0].deptName"},{"target":"tier","source":"$.tier"},{"target":"criticality","source":"$.criticality"},{"target":"status","source":"$.status"},{"target":"openIncidents","source":"$.metrics.openIncidents","transform":"TO_INT","default":0},{"target":"restartCount","source":"$.metrics.restartCount","transform":"TO_INT","default":0}]}',
       NULL, 3000, 'Y',
       'REST GET 1회로 CMDB 앱 상세(수 KB)를 받아, 장애 판단에 쓰는 앱ID/앱명/담당자/티어/상태/열린장애만 남긴다. appId 는 경로변수, 나머지는 Query String.', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'REST-CMDB-001');

-- 5) [INBOUND] 장애 이력 동기화: 내부 장애(INCIDENT) 이력을 순수 REST JSON Body 로 변환해 POST
--    MCP JSON-RPC 봉투 없이 {"source":"gsre-agent","incidents":[...]} 형태로 보낸다.
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, http_method, headers_config,
     auth_type, auth_config, request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'REST-INCIDENT-001', '장애 이력 동기화', 'incident.history.sync', 'INBOUND', 'REST',
       'http://localhost:18080/api/incidents/sync', 'POST',
       '{"X-Sync-Mode":"batch","X-GSRE-Source":"incident-agent-spring-ai"}',
       'NONE', NULL,
       '{"type":"object","properties":{"source":{"type":"string"},"incidents":{"type":"array"}}}',
       '{"version":"1.0","mode":"COLLECTION","itemPath":"$[*]","limit":50,"fields":[{"target":"issueKey","source":"$.issue_key","required":true},{"target":"projectKey","source":"$.project_key"},{"target":"appId","source":"$.app_id"},{"target":"appName","source":"$.app_name"},{"target":"assignee","source":"$.assignee_name"},{"target":"issueStatus","source":"$.status"},{"target":"summary","source":"$.summary","transform":"TRIM"},{"target":"hours","source":"$.worklog_hours","transform":"TO_NUMBER","default":0},{"target":"workDate","source":"$.worklog_date","transform":"TO_STRING"}],"envelope":{"type":"REST_JSON","payloadKey":"incidents","attributes":{"source":"gsre-agent","schemaVersion":"1.0"}}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"syncedCount","source":"$.syncedCount","transform":"TO_INT","default":0},{"target":"batchId","source":"$.batchId"},{"target":"status","source":"$.status","default":"UNKNOWN"},{"target":"processedAt","source":"$.processedAt"}]}',
       'SELECT j.issue_key, j.project_key, j.app_id, a.app_name, j.assignee_id, u.user_name AS assignee_name, j.issue_type, j.status, j.summary, j.worklog_hours, j.worklog_date FROM svcm_tb_jira_issue j LEFT JOIN svcm_tb_app_master a ON a.app_id = j.app_id LEFT JOIN svcm_tb_user_info_master u ON u.user_id = j.assignee_id WHERE j.issue_type = ''INCIDENT'' ORDER BY j.worklog_date DESC',
       5000, 'Y',
       '내부 장애 이력(svcm_tb_jira_issue, issue_type=INCIDENT)을 순수 REST JSON(incidents[])으로 변환해 동기화하고, 응답의 배치 접수 결과만 남긴다.', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'REST-INCIDENT-001');

-- 6) [BIDIRECTIONAL] SRE 알람 Webhook: CRITICAL 가동중(OPERATING) 자산 + 열린 장애 건수를 webhook 페이로드로 발송
--    인증은 API_KEY(환경변수 MCP_ALERT_API_KEY) — 값이 없으면 헤더만 생략하고 진행한다.
INSERT INTO svcm_tb_mcp_api_master
    (api_id, api_name, tool_name, direction, transport_type, endpoint_url, http_method, headers_config,
     auth_type, auth_config, request_schema, request_reshaping_rule, response_reshaping_rule, source_query,
     timeout_ms, active_yn, description, created_by)
SELECT 'REST-NOTIFY-001', 'SRE 알람 Webhook', 'sre.alert.webhook', 'BIDIRECTIONAL', 'REST',
       'http://localhost:18080/api/v1/webhooks/sre-alert', 'POST',
       '{"X-Webhook-Class":"sre-alert","X-GSRE-Source":"incident-agent-spring-ai"}',
       'API_KEY', '{"header":"X-Api-Key","envVar":"MCP_ALERT_API_KEY"}',
       '{"type":"object","properties":{"alerts":{"type":"array"}}}',
       '{"version":"1.0","mode":"COLLECTION","itemPath":"$[*]","fields":[{"target":"appId","source":"$.app_id","required":true},{"target":"appName","source":"$.app_name"},{"target":"severity","constant":"CRITICAL"},{"target":"serviceStatus","source":"$.status","default":"UNKNOWN"},{"target":"openIncidents","source":"$.open_incidents","transform":"TO_INT","default":0}],"envelope":{"type":"REST_JSON","payloadKey":"alerts","attributes":{"source":"gsre-agent","eventType":"asset.health","channel":"#sre-alerts"}}}',
       '{"version":"1.0","mode":"OBJECT","fields":[{"target":"notified","source":"$.notified","transform":"TO_INT","default":0},{"target":"channel","source":"$.channel","default":"#sre-alerts"},{"target":"status","source":"$.status","default":"UNKNOWN"},{"target":"receiptId","source":"$.receiptId"}]}',
       'SELECT a.app_id, a.app_name, a.criticality, a.status, COUNT(CASE WHEN j.status <> ''DONE'' THEN 1 END) AS open_incidents FROM svcm_tb_app_master a LEFT JOIN svcm_tb_jira_issue j ON j.app_id = a.app_id WHERE a.criticality = ''CRITICAL'' AND a.status = ''OPERATING'' GROUP BY a.app_id, a.app_name, a.criticality, a.status ORDER BY a.app_id',
       5000, 'Y',
       'CRITICAL 가동중 자산 2건을 webhook 페이로드로 변환해 POST 하고, 응답의 접수 건수/채널/영수증만 보관한다. (BIDIRECTIONAL: 내부 조회 + 외부 발송)', 'SEED'
WHERE NOT EXISTS (SELECT 1 FROM svcm_tb_mcp_api_master WHERE api_id = 'REST-NOTIFY-001');
