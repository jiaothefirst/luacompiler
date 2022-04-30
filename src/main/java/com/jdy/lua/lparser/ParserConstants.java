package com.jdy.lua.lparser;

public class ParserConstants {
    /**
     * 变量类型
     */
    public static int VDKREG  = 0; /* regular */
    public static int RDKCONST  = 1; /* constant */
    public static int RDKTOCLOSE  = 2;  /* to-be-closed */
    public static int RDKCTC  = 3; /* compile-time constant */


    public static final int FIELDS_PER_FLUSH = 50;

    public static final int LUA_MULTRET= -1;
}
