package com.xinshuo.mindflow.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    private String userId;

    private String username;

    private String role;

    private String avatar;
}
