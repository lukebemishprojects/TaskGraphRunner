module dev.lukebemish.taskgraphrunner.model {
    requires com.google.gson;
    requires static org.jspecify;
    requires dev.lukebemish.forkedtaskexecutor;
    requires com.github.oshi;

    opens dev.lukebemish.taskgraphrunner.model.conversion to com.google.gson;
    opens dev.lukebemish.taskgraphrunner.model to com.google.gson;
}
