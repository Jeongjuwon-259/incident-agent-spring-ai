-- =====================================================================
-- G-SRE 장애 지원 AI Agent - MCP API 확장 데모 시드
--   * 코드 재배포 없이 테이블 설정만으로 MCP 도구를 늘릴 수 있음을 보여주는 3건.
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
