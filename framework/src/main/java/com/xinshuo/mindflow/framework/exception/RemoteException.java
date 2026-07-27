package com.xinshuo.mindflow.framework.exception;

import com.xinshuo.mindflow.framework.errorcode.BaseErrorCode;
import com.xinshuo.mindflow.framework.errorcode.IErrorCode;

public class RemoteException extends AbstractException {

    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }
}
