package com.xinshuo.mindflow.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xinshuo.mindflow.framework.context.LoginUser;
import com.xinshuo.mindflow.framework.context.UserContext;
import com.xinshuo.mindflow.framework.convention.Result;
import com.xinshuo.mindflow.framework.web.Results;
import com.xinshuo.mindflow.user.controller.request.ChangePasswordRequest;
import com.xinshuo.mindflow.user.controller.request.UserCreateRequest;
import com.xinshuo.mindflow.user.controller.request.UserPageRequest;
import com.xinshuo.mindflow.user.controller.request.UserUpdateRequest;
import com.xinshuo.mindflow.user.controller.vo.CurrentUserVO;
import com.xinshuo.mindflow.user.controller.vo.UserVO;
import com.xinshuo.mindflow.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 * 提供当前登录用户信息查询接口
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        LoginUser user = UserContext.requireUser();
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                user.getAvatar()
        ));
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable("id") String id, @RequestBody UserUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前用户密码
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        userService.changePassword(requestParam);
        return Results.success();
    }
}

