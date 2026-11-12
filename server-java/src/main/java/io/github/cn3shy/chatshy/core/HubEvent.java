package io.github.cn3shy.chatshy.core;

import java.util.Map;

/** 广播到操作台的事件；null 字段不落 JSON（与 Node 版的对象形状一致）。 */
public record HubEvent(String type, String jobId, String threadId, Long deadlineAt, String message) {

    public static HubEvent jobCreated(String jobId, String threadId, long deadlineAt) {
        return new HubEvent("job.created", jobId, threadId, deadlineAt, null);
    }

    public static HubEvent jobEnded(String type, String jobId, String threadId) {
        return new HubEvent(type, jobId, threadId, null, null);
    }

    public static HubEvent threadDeleted(String threadId) {
        return new HubEvent("thread.deleted", null, threadId, null, null);
    }

    public static HubEvent forwardFailed(String jobId, String threadId, String message) {
        return new HubEvent("job.forward_failed", jobId, threadId, null, message);
    }

    public static HubEvent settingsUpdated() {
        return new HubEvent("settings.updated", null, null, null, null);
    }

    public static HubEvent providersUpdated() {
        return new HubEvent("providers.updated", null, null, null, null);
    }

    public static HubEvent ping() {
        return new HubEvent("ping", null, null, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = Json.obj();
        map.put("type", type);
        if (jobId != null) map.put("jobId", jobId);
        if (threadId != null) map.put("threadId", threadId);
        if (deadlineAt != null) map.put("deadlineAt", deadlineAt);
        if (message != null) map.put("message", message);
        return map;
    }

    public String toFrame() {
        return "data: " + Json.stringify(toMap()) + "\n\n";
    }
}
