package com.xinshuo.mindflow.framework.exception;

import com.xinshuo.mindflow.framework.errorcode.BaseErrorCode;
import com.xinshuo.mindflow.framework.errorcode.IErrorCode;

public class ClientException extends AbstractException {

    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }
}
