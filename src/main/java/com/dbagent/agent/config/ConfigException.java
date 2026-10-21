package com.dbagent.agent.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 설정 오류 모음. 하나씩 고치며 재기동하지 않도록 발견한 오류를 한꺼번에 알린다(1-A1). */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    ConfigException(List<String> errors) {
        super(errors.size() + " configuration error(s): " + errors);
        this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
    }

    public List<String> getErrors() {
        return errors;
    }
}
