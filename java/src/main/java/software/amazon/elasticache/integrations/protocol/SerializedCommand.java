// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.protocol;

import java.util.List;

/** A serialized command ready to be encoded to RESP3 wire format. */
public class SerializedCommand {

    private final String commandName;
    private final List<byte[]> args;

    /**
     * Create a serialized command.
     *
     * @param commandName the command name
     * @param args the command arguments
     */
    public SerializedCommand(String commandName, List<byte[]> args) {
        this.commandName = commandName;
        this.args = args;
    }

    /**
     * Return the command name.
     *
     * @return the command name
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * Return the command arguments.
     *
     * @return the command arguments
     */
    public List<byte[]> getArgs() {
        return args;
    }
}
