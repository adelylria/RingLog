package com.adelylria.ringlog.storage;

interface PathEnvironment {

    String environmentVariable(String name);

    String systemProperty(String name);
}
