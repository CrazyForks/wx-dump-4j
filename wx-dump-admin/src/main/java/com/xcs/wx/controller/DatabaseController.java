package com.xcs.wx.controller;

import cn.hutool.system.SystemUtil;
import com.xcs.wx.domain.dto.DecryptDTO;
import com.xcs.wx.domain.vo.ResponseVO;
import com.xcs.wx.domain.vo.WeChatVO;
import com.xcs.wx.service.DatabaseService;
import com.xcs.wx.service.WeChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库 Controller
 *
 * @author xcs
 * @date 2024年01月20日 14时35分
 **/
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/database")
public class DatabaseController {

    private final DatabaseService databaseService;
    private final WeChatService weChatService;

    /**
     * 数据库解密
     *
     * @return ResponseVO
     */
    @GetMapping("/decrypt")
    public ResponseVO<Boolean> decrypt(DecryptDTO decryptDTO) {
        // 读取JDK版本号
        if (SystemUtil.getJavaInfo().getVersionInt() < 1100) {
            return ResponseVO.error(-1, "微信解密必须要求JDK11以上版本,请更换JDK版本。");
        }
        databaseService.decrypt(decryptDTO);
        // 返回数据
        return ResponseVO.ok(true);
    }

    /**
     * 查询微信信息
     *
     * @return ResponseVO
     */
    @GetMapping("/queryWeChat")
    public ResponseVO<WeChatVO> queryWeChat() {
        return ResponseVO.ok(weChatService.queryWeChat());
    }
}
