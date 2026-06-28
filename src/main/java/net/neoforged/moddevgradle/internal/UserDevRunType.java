package net.neoforged.moddevgradle.internal;

import java.util.List;
import java.util.Map;

public record UserDevRunType(boolean singleInstance, String main, List<String> args, List<String> jvmArgs,
        Map<String, String> env, Map<String, String> props) {
    public UserDevRunType {
        args = args == null ? List.of() : args;
        jvmArgs = jvmArgs == null ? List.of() : jvmArgs;
        env = env == null ? Map.of() : env;
        props = props == null ? Map.of() : props;
    }
}
