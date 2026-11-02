package software.amazon.elasticache.integrations.protocol;

import java.util.List;

/** A serialized command ready to be encoded to RESP3 wire format. */
public class SerializedCommand {

    private final String commandName;
    private final List<byte[]> args;

    public SerializedCommand(String commandName, List<byte[]> args) {
        this.commandName = commandName;
        this.args = args;
    }

    public String getCommandName() {
        return commandName;
    }

    public List<byte[]> getArgs() {
        return args;
    }
}
