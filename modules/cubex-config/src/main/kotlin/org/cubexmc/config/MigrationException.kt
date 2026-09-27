package org.cubexmc.config

public class MigrationException : Exception {
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
}
