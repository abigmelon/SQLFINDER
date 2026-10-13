package com.sqlfinder.settings;

import java.io.IOException;

/** 模型传输接口 */
public interface DeepSeekTransport {
    Response send(String path, ModelConfiguration configuration, String body) throws IOException, InterruptedException;
    record Response(int status, String body) { }
}
