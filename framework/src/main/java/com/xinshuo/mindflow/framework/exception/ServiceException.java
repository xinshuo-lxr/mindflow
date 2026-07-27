package com.xinshuo.mindflow.framework.exception;

import com.xinshuo.mindflow.framework.errorcode.BaseErrorCode;
import com.xinshuo.mindflow.framework.errorcode.IErrorCode;

public class ServiceException extends AbstractException {

    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }
}
