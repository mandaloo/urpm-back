package com.example.urpm.config.shiro;

import com.auth0.jwt.exceptions.JWTDecodeException;
import com.example.urpm.common.exception.JwtTokenException;
import com.example.urpm.config.shiro.jwt.JWTToken;
import com.example.urpm.mapper.PermissionMapper;
import com.example.urpm.mapper.RoleMapper;
import com.example.urpm.model.common.Constant;
import com.example.urpm.model.dto.PermissionDto;
import com.example.urpm.model.dto.RoleDto;
import com.example.urpm.model.entity.Permission;
import com.example.urpm.service.PermissionService;
import com.example.urpm.service.RoleService;
import com.example.urpm.service.UserService;
import com.example.urpm.util.JWTUtil;
import com.example.urpm.util.JedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author dingjinyang
 * @datetime 2020/2/12 17:45
 * @description 自定义User Realm
 */
@Slf4j
@Service
public class UserRealm extends AuthorizingRealm {

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;

    @Autowired
    public UserRealm(PermissionMapper permissionMapper, RoleMapper roleMapper) {
        this.permissionMapper = permissionMapper;
        this.roleMapper = roleMapper;
    }


    @Override
    public boolean supports(AuthenticationToken authenticationToken) {
        return authenticationToken instanceof JWTToken;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principalCollection) {
        SimpleAuthorizationInfo simpleAuthorizationInfo = new SimpleAuthorizationInfo();
        String account = principalCollection.toString();
        log.debug("==> UserRealm doGetAuthorizationInfo Start：{} ", account);
        // 查询用户角色
        List<RoleDto> roleDtos = roleMapper.selectRoleByUserAccount(account);
        roleDtos.forEach(roleDto -> {
            if (roleDto != null) {
                // 添加角色
                simpleAuthorizationInfo.addRole(roleDto.getName());
                // 根据用户角色查询权限
                List<PermissionDto> permissionDtos = permissionMapper.selectPermissionByRoleId(roleDto.getId());
                permissionDtos.forEach(permissionDto -> {
                    if (permissionDto != null) {
                        // 添加权限
                        simpleAuthorizationInfo.addStringPermission(permissionDto.getPerCode());
                    }
                });
            }
        });
        log.debug("==> UserRealm doGetAuthorizationInfo End ：{} ", account);
        return simpleAuthorizationInfo;
    }

    /**
     * 使用此方法进行用户名正确与否验证，错误抛出异常即可。
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken authenticationToken) throws AuthenticationException {
        String token = (String) authenticationToken.getCredentials();
        // 解密获得account，用于和数据库进行对比
        String account;
        try {
            account = JWTUtil.getClaim(token, Constant.ACCOUNT);
        } catch (JWTDecodeException e) {
            throw new AuthenticationException("Token 有误 ！");
        }
        log.debug("==> UserRealm doGetAuthenticationInfo，JWT decode Account : {}", account);
        // 开始认证，要AccessToken认证通过，且Redis中存在RefreshToken，且两个Token时间戳一致
        if (JWTUtil.verify(token) && JedisUtil.exists(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account)) {
            // 获取RefreshToken的时间戳
            String redisTimestamp = JedisUtil.get(Constant.PREFIX_SHIRO_REFRESH_TOKEN + account);
            String jwtTimestamp = JWTUtil.getClaim(token, Constant.CURRENT_TIME_MILLIS);
            // 双重认证： Jwt时间戳  与  Redis refresh_token时间戳对比
            log.debug("==> Timestamp equals Jwt: {} | redis ：{}", jwtTimestamp, redisTimestamp);
            if (jwtTimestamp.equals(redisTimestamp)) {
                return new SimpleAuthenticationInfo(account, token, "userRealm");
            }
        }
        throw new JwtTokenException();
    }
}