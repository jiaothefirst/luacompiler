package com.jdy.lua.lex;

import lombok.Data;

@Data
public class Token {

    /**
     * token类型值
     */
    int token;

    double r;
    long i;
    String s;
}