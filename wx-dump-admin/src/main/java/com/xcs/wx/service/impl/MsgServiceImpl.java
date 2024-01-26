package com.xcs.wx.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import com.alibaba.excel.EasyExcel;
import com.google.protobuf.InvalidProtocolBufferException;
import com.xcs.wx.constant.ChatRoomConstant;
import com.xcs.wx.domain.vo.ExportMsgVO;
import com.xcs.wx.domain.vo.MsgVO;
import com.xcs.wx.domain.vo.WeChatVO;
import com.xcs.wx.mapping.MsgMapping;
import com.xcs.wx.msg.MsgStrategy;
import com.xcs.wx.msg.MsgStrategyFactory;
import com.xcs.wx.protobuf.MsgProto;
import com.xcs.wx.repository.ContactHeadImgUrlRepository;
import com.xcs.wx.repository.ContactRepository;
import com.xcs.wx.repository.MsgRepository;
import com.xcs.wx.service.MsgService;
import com.xcs.wx.util.UserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 消息服务实现类
 *
 * @author xcs
 * @date 2023年12月25日 17时04分
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class MsgServiceImpl implements MsgService {

    private final MsgRepository msgRepository;
    private final MsgMapping msgMapping;
    private final ContactHeadImgUrlRepository contactHeadImgUrlRepository;
    private final ContactRepository contactRepository;

    @Override
    public List<MsgVO> queryMsg(String talker, Long lastCreateTime) {
        // 根据Id查询聊天记录
        return Optional.ofNullable(msgRepository.queryMsgByTalker(talker, lastCreateTime))
                // 处理消息
                .map(msgs -> {
                    // 获取用户信息
                    WeChatVO user = UserUtil.getUser();
                    // 参数转换
                    List<MsgVO> msgVOList = msgMapping.convert(msgs);
                    // 根据时间排序
                    msgVOList.stream().sorted(Comparator.comparing(MsgVO::getCreateTime))
                            // 遍历数据
                            .forEach(msgVO -> {
                                msgVO.setWxId(getChatWxId(talker, msgVO, user));
                                // 设置处理日期
                                msgVO.setStrCreateTime(DateUtil.formatDateTime(new Date(msgVO.getCreateTime() * 1000)));
                                // 设置聊天头像
                                msgVO.setAvatar(getChatAvatar(msgVO.getWxId()));
                                // 读取消息类型策略
                                MsgStrategy strategy = MsgStrategyFactory.getStrategy(msgVO.getType(), msgVO.getSubType());
                                // 根据对应的策略进行处理
                                if (strategy != null) {
                                    strategy.process(msgVO);
                                }
                            });
                    return msgVOList;
                })
                // 设置默认值
                .orElse(Collections.emptyList());
    }

    @Override
    public String exportMsg(String talker) {
        // 根据Id查询聊天记录
        List<MsgVO> msgVOList = Optional.ofNullable(msgRepository.exportMsg(talker))
                // 处理消息
                .map(msgs -> {
                    // 获取用户信息
                    WeChatVO user = UserUtil.getUser();
                    // 根据时间排序
                    return msgMapping.convert(msgs).stream().sorted(Comparator.comparing(MsgVO::getCreateTime))
                            // 遍历数据
                            .peek(msgVO -> {
                                msgVO.setWxId(getChatWxId(talker, msgVO, user));
                                // 设置处理日期
                                msgVO.setStrCreateTime(DateUtil.formatDateTime(new Date(msgVO.getCreateTime() * 1000)));
                                // 读取消息类型策略
                                MsgStrategy strategy = MsgStrategyFactory.getStrategy(msgVO.getType(), msgVO.getSubType());
                                // 根据对应的策略进行处理
                                if (strategy != null) {
                                    strategy.process(msgVO);
                                }
                            }).collect(Collectors.toList());
                })
                // 设置默认值
                .orElse(Collections.emptyList());

        // 聊天人的昵称
        String nickname = contactRepository.getContactNickname(talker);
        // 分隔符
        String separator = System.getProperty("file.separator");
        // 文件路径
        String filePath = System.getProperty("user.dir") + separator + "export";
        // 创建文件
        FileUtil.mkdir(filePath);
        // 文件路径+文件名
        String pathName = filePath + separator + DateUtil.format(DateUtil.date(), "yyyyMMddHHmmss") + nickname + ".xlsx";
        // 导出
        EasyExcel.write(pathName, ExportMsgVO.class)
                .sheet("sheet1")
                .doWrite(() -> msgMapping.convertToExportMsgVO(msgVOList));
        // 返回写入后的文件
        return pathName;
    }

    /**
     * 获取对话人Id
     *
     * @param talker 聊天对话者
     * @param msgVO  消息VO
     * @param user   用户信息
     * @return wxid
     */
    private String getChatWxId(String talker, MsgVO msgVO, WeChatVO user) {
        String wxId = null;
        // 我发送的消息
        if (msgVO.getIsSender() == 1) {
            wxId = user.getWxId();
        } else {
            // 我接受的消息
            try {
                // 群聊
                if (talker.endsWith(ChatRoomConstant.CHATROOM_SUFFIX)) {
                    MsgProto.MessageBytesExtra messageBytesExtra = MsgProto.MessageBytesExtra.parseFrom(msgVO.getBytesExtra());
                    List<MsgProto.SubMessage2> message2List = messageBytesExtra.getMessage2List();
                    for (MsgProto.SubMessage2 subMessage2 : message2List) {
                        if (subMessage2.getField1() == 1) {
                            wxId = subMessage2.getField2();
                        }
                    }
                } else {
                    wxId = talker;
                }
            } catch (InvalidProtocolBufferException e) {
                log.error("获取对话人Id失败", e);
            }
        }
        return wxId;
    }

    /**
     * 聊天头像
     *
     * @param wxId 聊天对话者
     */
    private String getChatAvatar(String wxId) {
        return contactHeadImgUrlRepository.queryHeadImgUrlByUserName(wxId);
    }
}