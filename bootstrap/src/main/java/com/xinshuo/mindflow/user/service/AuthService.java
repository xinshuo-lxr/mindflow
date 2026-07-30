package com.xinshuo.mindflow.user.service;

import com.xinshuo.mindflow.user.controller.request.LoginRequest;
import com.xinshuo.mindflow.user.controller.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginRequest requestParam);

    void logout();
}
