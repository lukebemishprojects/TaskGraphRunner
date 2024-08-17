package dev.lukebemish.taskgraphmodel.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

public record Config(List<TaskModel> tasks, List<WorkItem> workItems) {
    JsonElement toJson() {
        JsonObject json = new JsonObject();
        var workItemsArray = new JsonArray();
        for (WorkItem workItem : workItems) {
            workItemsArray.add(workItem.toJson());
        }
        JsonArray tasksArray = new JsonArray();
        for (TaskModel task : tasks) {
            tasksArray.add(task.toJson());
        }
        json.add("workItems", workItemsArray);
        json.add("tasks", tasksArray);
        return json;
    }

    static Config fromJson(JsonElement json) {
        if (json.isJsonObject()) {
            var jsonObject = json.getAsJsonObject();
            var workItemsArray = jsonObject.getAsJsonArray("workItems");
            var workItems = workItemsArray.asList().stream().map(WorkItem::fromJson).toList();
            var tasksArray = jsonObject.getAsJsonArray("tasks");
            var tasks = tasksArray.asList().stream().map(TaskModel::fromJson).toList();
            return new Config(tasks, workItems);
        } else {
            throw new IllegalArgumentException("Not an object `" + json + "`");
        }
    }
}
