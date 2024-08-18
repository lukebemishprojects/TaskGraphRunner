module dev.lukebemish.taskgraphrunner.model {
    requires com.google.gson;
    requires org.jspecify;

    opens dev.lukebemish.taskgraphrunner.model.neoform to com.google.gson;
    opens dev.lukebemish.taskgraphrunner.model to com.google.gson;
}
