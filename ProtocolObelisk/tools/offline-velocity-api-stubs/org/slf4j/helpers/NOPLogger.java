package org.slf4j.helpers;
import org.slf4j.Logger;
public final class NOPLogger implements Logger { public static final NOPLogger NOP_LOGGER = new NOPLogger(); private NOPLogger(){} }
