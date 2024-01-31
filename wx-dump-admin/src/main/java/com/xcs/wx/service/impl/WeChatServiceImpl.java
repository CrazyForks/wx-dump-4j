package com.xcs.wx.service.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.xcs.wx.config.WeChatOffsetProperties;
import com.xcs.wx.domain.vo.WeChatVO;
import com.xcs.wx.exception.BizException;
import com.xcs.wx.service.WeChatService;
import com.xcs.wx.util.UserUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

/**
 * 微信服务实现类
 *
 * @author xcs
 * @date 2023年12月25日 09时37分
 **/
@Service
@RequiredArgsConstructor
public class WeChatServiceImpl implements WeChatService {

    /**
     * 微信进程
     */
    private static final String EXE_NAME = "WeChat.exe";

    /**
     * 微信程序dll文件
     */
    private static final String MODULE_NAME = "WeChatWin.dll";

    /**
     * 文档目录
     */
    private static final String MY_DOCUMENT = "MyDocument:";

    /**
     * 微信内存地址偏移量配置
     */
    private final WeChatOffsetProperties weChatOffsetConfig;

    @Override
    public WeChatVO queryWeChat() {
        // 获取微信进程Id
        int pid = pid();
        // 微信未登录
        if (pid == 0) {
            throw new BizException(-1, "检测到微信尚未启动，请先打开微信以继续进行操作。");
        }
        // 获取微信的基址
        long baseAddress = baseAddress(pid);
        // 获取版本号
        String version = getVersion(pid);
        // 读取微信版本对应的偏移量
        WeChatOffsetProperties.VersionConfig versionConfig = weChatOffsetConfig.getVersion().get(version);
        // 获取微信昵称
        String nickname = getInfo(pid, (baseAddress + versionConfig.getNickname()));
        // 获取微信账号
        String account = getInfo(pid, (baseAddress + versionConfig.getAccount()));
        // 获取微信手机号
        String mobile = getInfo(pid, (baseAddress + versionConfig.getMobile()));
        // 获取微信秘钥
        String key = getKey(pid, (baseAddress + versionConfig.getKey()));
        // 打开了微信，但是未登录状态
        if (StrUtil.isBlank(key)) {
            throw new BizException(-1, "获取微信秘钥失败，请稍后再试。");
        }
        // 获取微信文件目录
        String basePath = getBasePath();
        // 获取微信Id
        String wxId = getWxId(pid);
        // 返回配置信息
        WeChatVO weChatVO = new WeChatVO(pid, baseAddress, version, nickname, account, mobile, key, basePath, wxId);
        // 保存用户信息
        UserUtil.saveUser(weChatVO);
        // 返回
        return weChatVO;
    }

    /**
     * 获取当前运行的微信进程的进程 ID。
     *
     * @return 微信进程的进程 ID。如果未找到，返回 0。
     */
    private int pid() {
        // 实例化Kernel32库接口
        Kernel32 kernel32 = Kernel32.INSTANCE;

        // 创建系统快照，用来枚举所有进程
        WinNT.HANDLE hSnapshot = kernel32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        // 初始化进程入口结构体
        Tlhelp32.PROCESSENTRY32.ByReference pe32 = new Tlhelp32.PROCESSENTRY32.ByReference();

        // 遍历所有进程
        if (kernel32.Process32First(hSnapshot, pe32)) {
            do {
                // 获取进程名
                String exeName = Native.toString(pe32.szExeFile);
                // 检查进程名是否为目标进程名
                if (exeName.equalsIgnoreCase(EXE_NAME)) {
                    // 关闭快照句柄
                    kernel32.CloseHandle(hSnapshot);
                    // 返回找到的进程ID
                    return pe32.th32ProcessID.intValue();
                }
                // 继续遍历下一个进程
            } while (kernel32.Process32Next(hSnapshot, pe32));
        }
        // 关闭快照句柄
        kernel32.CloseHandle(hSnapshot);
        // 如果没有找到进程，返回0
        return 0;
    }

    /**
     * 根据提供的进程 ID 找到相应进程的模块基地址。
     *
     * @param pid 目标进程的 ID。
     * @return 进程的模块基地址，如果找不到则返回 0。
     */
    private long baseAddress(int pid) {
        Kernel32 kernel32 = Kernel32.INSTANCE;

        // 创建模块快照
        WinNT.HANDLE hModuleSnapshot = kernel32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPMODULE, new WinDef.DWORD(pid));

        // 确保快照创建成功
        if (!WinNT.INVALID_HANDLE_VALUE.equals(hModuleSnapshot)) {
            // 初始化 MODULEENTRY32W 结构体的引用
            Tlhelp32.MODULEENTRY32W.ByReference me32 = new Tlhelp32.MODULEENTRY32W.ByReference();

            // 遍历所有模块
            if (kernel32.Module32FirstW(hModuleSnapshot, me32)) {
                do {
                    // 从模块入口结构中读取模块名
                    String moduleName = Native.toString(me32.szModule);
                    // 检查是否为微信 DLL
                    if (MODULE_NAME.equalsIgnoreCase(moduleName)) {
                        // 返回微信 DLL 的基地址
                        kernel32.CloseHandle(hModuleSnapshot);
                        return Pointer.nativeValue(me32.modBaseAddr);
                    }
                } while (kernel32.Module32NextW(hModuleSnapshot, me32));
            }
            // 关闭快照句柄
            kernel32.CloseHandle(hModuleSnapshot);
        }
        // 如果未找到微信 DLL，返回 0
        return 0;
    }

    /**
     * 从指定进程的指定内存地址读取信息。
     *
     * @param pid     目标进程的 ID。
     * @param address 要读取的内存地址。
     * @return 读取到的数据，如果失败则返回 null。
     */
    private String getInfo(int pid, long address) {
        Kernel32 kernel32 = Kernel32.INSTANCE;
        // 打开目标进程
        WinNT.HANDLE process = kernel32.OpenProcess(WinNT.PROCESS_VM_READ, false, pid);

        // 分配内存作为缓冲区读取数据
        int bufferSize = 64;
        Memory buffer = new Memory(bufferSize);
        IntByReference read = new IntByReference();

        // 从指定地址读取内存
        boolean success = kernel32.ReadProcessMemory(process, new Pointer(address), buffer, bufferSize, read);

        if (success) {
            // 将读取的字节转换为字符串
            byte[] dataBytes = buffer.getByteArray(0, read.getValue());
            String data = new String(dataBytes, 0, read.getValue());

            // 查找第一个空字符（字符串结束标志）
            int nullPos = data.indexOf('\0');
            if (nullPos != -1) {
                // 截取到第一个空字符为止的字符串
                data = data.substring(0, nullPos);
            }

            // 返回读取的字符串，如果为空则返回 null
            kernel32.CloseHandle(process);
            return data.isEmpty() ? null : data;
        }

        // 关闭进程句柄
        kernel32.CloseHandle(process);

        return null;
    }

    /**
     * 获取秘钥
     *
     * @param pid     目标进程的 ID。
     * @param address 要读取的内存地址
     * @return 读取到的秘钥，如果失败则返回 null。
     */
    private String getKey(int pid, long address) {
        Kernel32 kernel32 = Kernel32.INSTANCE;

        // 打开目标进程，获取其句柄
        WinNT.HANDLE process = kernel32.OpenProcess(WinNT.PROCESS_VM_READ, false, pid);

        // 准备缓冲区用于读取内存
        Memory buffer = new Memory(8);
        IntByReference bytesRead = new IntByReference();
        // 读取目标进程的内存地址
        if (!kernel32.ReadProcessMemory(process, new Pointer(address), buffer, (int) buffer.size(), bytesRead)) {
            // 如果读取失败，关闭进程句柄并返回 null
            kernel32.CloseHandle(process);
            return null;
        }

        // 将缓冲区中的字节转换为长整型值
        ByteBuffer bufferWrap = ByteBuffer.wrap(buffer.getByteArray(0, 8)).order(ByteOrder.LITTLE_ENDIAN);
        long keyAddress = bufferWrap.getLong();

        // 准备一个缓冲区用于存放密钥
        Memory keyBuffer = new Memory(32);
        // 从计算出的密钥地址读取密钥
        if (!kernel32.ReadProcessMemory(process, new Pointer(keyAddress), keyBuffer, (int) keyBuffer.size(), bytesRead)) {
            // 如果读取密钥失败，关闭进程句柄并返回 null
            kernel32.CloseHandle(process);
            return null;
        }

        // 将密钥转换为十六进制字符串
        StringBuilder sb = new StringBuilder();
        byte[] key = keyBuffer.getByteArray(0, 32);
        try (Formatter formatter = new Formatter(sb)) {
            for (byte b : key) {
                formatter.format("%02x", b & 0xff);
            }
        }

        // 关闭进程句柄
        kernel32.CloseHandle(process);

        // 返回密钥的十六进制表示
        return sb.toString();
    }

    /**
     * 获取微信目录
     *
     * @return 微信文件目录
     */
    private String getBasePath() {
        // 准备一个数组来存储用户主目录的路径
        char[] userHomePath = new char[WinDef.MAX_PATH];

        // 使用 Shell32 库的 SHGetFolderPath 方法获取用户主目录路径
        // 如果调用失败，则返回 null
        if (Shell32.INSTANCE.SHGetFolderPath(null, ShlObj.CSIDL_PROFILE, null, ShlObj.SHGFP_TYPE_CURRENT, userHomePath).intValue() == WinError.S_FALSE.intValue()) {
            return null;
        }

        // 构建微信配置文件的路径
        String iniFilePath = Native.toString(userHomePath) + "\\AppData\\Roaming\\Tencent\\WeChat\\All Users\\config\\3ebffe94.ini";

        try {
            // 读取配置文件中的第一行
            List<String> lines = Files.readAllLines(Paths.get(iniFilePath));

            // 如果文件为空，返回 null
            if (lines.isEmpty()) {
                return null;
            }

            // 获取文件中的文本内容
            String txt = lines.get(0);

            // 如果内容为 "MyDocument:"，则获取文档目录
            if (MY_DOCUMENT.equals(txt)) {
                char[] documentsPath = new char[WinDef.MAX_PATH];
                // 再次使用 SHGetFolderPath 方法获取文档目录路径
                if (Shell32.INSTANCE.SHGetFolderPath(null, ShlObj.CSIDL_PERSONAL, null, ShlObj.SHGFP_TYPE_CURRENT, documentsPath).intValue() == WinError.S_OK.intValue()) {
                    // 返回微信文件夹的完整路径
                    return Native.toString(documentsPath) + "\\WeChat Files";
                }
            } else {
                // 如果内容不是 "MyDocument:"，则使用读取的路径
                return txt + "\\WeChat Files";
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 如果无法获取路径，则返回 null
        return null;
    }

    /**
     * 根据进程ID获取微信ID。
     *
     * @param pid 目标进程ID
     * @return 微信ID
     */
    private String getWxId(int pid) {
        // 获取Kernel32实例
        Kernel32 kernel32 = Kernel32.INSTANCE;
        // 打开目标进程句柄
        WinNT.HANDLE process = kernel32.OpenProcess(0x1F0FFF, false, pid);
        // 设置要匹配的模式字符串
        String pattern = "\\Msg\\FTSContact";
        // 在目标进程中扫描符合模式的内存位置
        List<Pointer> addresses = patternScanAll(process, pattern, 10);
        // 遍历匹配到的内存位置
        for (Pointer address : addresses) {
            // 分配内存作为缓冲区读取数据
            int bufferSize = 80;
            Memory buffer = new Memory(bufferSize);
            IntByReference read = new IntByReference();
            // 从指定地址的前一部分开始读取内存，而不是从地址的确切位置开始。
            Pointer offsetPointer = new Pointer(Pointer.nativeValue(address) - 30);
            // 从指定地址读取内存数据
            boolean success = kernel32.ReadProcessMemory(process, offsetPointer, buffer, bufferSize, read);
            if (success) {
                // 将读取的字节转换为字符串
                byte[] dataBytes = buffer.getByteArray(0, read.getValue());
                String data = new String(dataBytes, 0, read.getValue());
                // 对字符串进行处理，提取微信ID信息
                data = data.split("\\\\Msg")[0];
                data = data.split("\\\\")[1];
                // 返回微信Id
                return data;
            }
        }
        // 关闭目标进程句柄
        kernel32.CloseHandle(process);
        // 返回空
        return null;
    }

    /**
     * 在指定进程的虚拟地址空间中扫描所有符合指定模式的内存位置。
     *
     * @param process 指定进程的句柄
     * @param pattern 要匹配的模式字符串
     * @param findNum 最大匹配数量
     * @return 符合模式的内存位置列表
     */
    public List<Pointer> patternScanAll(WinNT.HANDLE process, String pattern, int findNum) {
        // 存储符合模式的内存位置列表
        List<Pointer> found = new ArrayList<>();
        // 根据系统架构设置用户空间限制
        long userSpaceLimit = "amd64".equals(System.getProperty("os.arch")) ? 0x7FFFFFFF0000L : 0x7FFF0000L;
        // 初始下一个扫描位置
        long nextRegion = 0;
        // 循环扫描直到达到用户空间限制或找到足够的匹配位置
        while (nextRegion < userSpaceLimit) {
            // 执行单页扫描，获取扫描结果
            Pair<Long, List<Pointer>> pair = scanPatternPage(process, nextRegion, pattern);
            // 更新下一个扫描位置
            nextRegion = pair.getLeft();
            // 获取单页扫描的匹配位置列表
            List<Pointer> pageFound = pair.getRight();

            // 如果单页扫描找到匹配位置，将其添加到总的匹配位置列表中
            if (!pageFound.isEmpty()) {
                found.addAll(pageFound);
            }
            // 如果找到足够的匹配位置，跳出循环
            if (found.size() > findNum) {
                break;
            }
        }
        // 返回符合模式的内存位置列表
        return found;
    }

    /**
     * 扫描内存页
     *
     * @param process      指定进程的句柄
     * @param startAddress 开始的内存地址
     * @param pattern      要匹配的模式字符串
     * @return Pair
     */
    private Pair<Long, List<Pointer>> scanPatternPage(WinNT.HANDLE process, long startAddress, String pattern) {
        // 获取内存基本信息
        WinNT.MEMORY_BASIC_INFORMATION mbi = new WinNT.MEMORY_BASIC_INFORMATION();
        // 查询指定虚拟内存地址
        Kernel32.INSTANCE.VirtualQueryEx(process, new Pointer(startAddress), mbi, new BaseTSD.SIZE_T(mbi.size()));
        // 计算下一个内存区域的起始地址
        long nextRegion = Pointer.nativeValue(mbi.baseAddress) + mbi.regionSize.longValue();
        // 定义允许的内存保护标志
        int[] allowedProtections = {WinNT.PAGE_EXECUTE, WinNT.PAGE_EXECUTE_READ, WinNT.PAGE_EXECUTE_READWRITE, WinNT.PAGE_READWRITE, WinNT.PAGE_READONLY};

        List<Pointer> foundPointer = new ArrayList<>();

        // 检查内存状态和保护标志是否符合要求
        if (!(mbi.state.intValue() == WinNT.MEM_COMMIT && ArrayUtil.contains(allowedProtections, mbi.protect.intValue()))) {
            return Pair.of(nextRegion, foundPointer);
        }

        // 创建一个 Native Memory 对象，用于存储从进程中读取的内存数据
        Memory memory = new Memory(mbi.regionSize.intValue());
        // 创建一个引用对象，用于存储 ReadProcessMemory 函数返回的已读取字节数
        IntByReference bytesRead = new IntByReference();
        // 从指定进程中读取内存数据，并将结果存储到 Native Memory 对象中
        Kernel32.INSTANCE.ReadProcessMemory(process, new Pointer(startAddress), memory, mbi.regionSize.intValue(), bytesRead);
        // 从 Native Memory 对象中获取已读取的字节数，并创建一个字节数组存储读取的内存数据
        byte[] buffer = memory.getByteArray(0, bytesRead.getValue());

        // 查找匹配的模式在内存中的起始位置
        for (int start : findMatches(buffer, pattern.getBytes())) {
            foundPointer.add(new Pointer(startAddress + start));
        }
        return Pair.of(nextRegion, foundPointer);
    }

    /**
     * 在字节数组中查找匹配指定模式的位置索引数组。
     *
     * @param inputBytes   待查找的字节数组
     * @param patternBytes 要匹配的模式字节数组
     * @return 匹配位置索引数组
     */
    private static int[] findMatches(byte[] inputBytes, byte[] patternBytes) {
        // 初始化匹配位置索引数组
        int[] matches = new int[0];

        // 遍历待查找的字节数组
        for (int i = 0; i <= inputBytes.length - patternBytes.length; i++) {
            // 假设当前位置开始存在匹配
            boolean match = true;

            // 遍历模式字节数组，检查是否与待查找的子数组匹配
            for (int j = 0; j < patternBytes.length; j++) {
                if (inputBytes[i + j] != patternBytes[j]) {
                    // 如果有不匹配的字节，标记为不匹配，并中断内层循环
                    match = false;
                    break;
                }
            }
            // 如果找到匹配的位置，将其添加到匹配位置索引数组中
            if (match) {
                int[] newMatches = new int[matches.length + 1];
                System.arraycopy(matches, 0, newMatches, 0, matches.length);
                newMatches[matches.length] = i;
                matches = newMatches;
            }
        }
        // 返回匹配位置索引数组
        return matches;
    }

    /**
     * 获取指定进程ID的可执行文件版本。
     *
     * @param pid 进程ID。
     * @return 文件的版本号，如果无法获取，则返回 null。
     */
    public String getVersion(int pid) {
        // 获取指定进程ID的可执行文件路径。
        String filePath = getExecutablePath(pid);

        // 用于存储 'GetFileVersionInfoSize' 函数的额外信息（通常未使用）。
        IntByReference dwDummy = new IntByReference();
        dwDummy.setValue(0);

        // 获取文件版本信息的大小。
        int versionLength = Version.INSTANCE.GetFileVersionInfoSize(filePath, dwDummy);
        // 如果长度为0，表示没有版本信息。
        if (versionLength == 0) {
            return null;
        }

        // 分配足够存储文件版本信息的缓冲区。
        byte[] buffer = new byte[versionLength];
        // 使用 JNA 的 Memory 类将缓冲区映射到内存。
        Pointer lpData = new Memory(buffer.length);
        // 获取文件版本信息。
        if (!Version.INSTANCE.GetFileVersionInfo(filePath, 0, versionLength, lpData)) {
            return null;
        }

        // 用于存储 'VerQueryValue' 函数输出的文件版本信息指针。
        PointerByReference lplpBuffer = new PointerByReference();
        // 用于存储 'VerQueryValue' 函数输出的文件版本信息长度。
        IntByReference puLen = new IntByReference();

        // 查询具体的文件版本信息。
        if (Version.INSTANCE.VerQueryValue(lpData, "\\", lplpBuffer, puLen)) {
            // 将指针映射到 VS_FIXEDFILEINFO 结构。
            VerRsrc.VS_FIXEDFILEINFO lplpBufStructure = new VerRsrc.VS_FIXEDFILEINFO(lplpBuffer.getValue());
            lplpBufStructure.read();

            // 提取文件版本的主要部分和次要部分。
            long v1 = (lplpBufStructure.dwFileVersionMS.longValue() >> 16) & 0xffff;
            long v2 = lplpBufStructure.dwFileVersionMS.longValue() & 0xffff;
            long v3 = (lplpBufStructure.dwFileVersionLS.longValue() >> 16) & 0xffff;
            long v4 = lplpBufStructure.dwFileVersionLS.longValue() & 0xffff;

            // 组合并返回版本字符串。
            return v1 + "." + v2 + "." + v3 + "." + v4;
        }

        // 如果无法查询到版本信息，返回 null。
        return null;
    }

    /**
     * 获取给定进程ID的可执行文件路径。
     *
     * @param pid 进程ID。
     * @return 可执行文件的完整路径，如果无法获取，则返回 null。
     */
    private String getExecutablePath(int pid) {
        // 获取 Kernel32 实例，用于访问 Windows API 函数。
        Kernel32 kernel32 = Kernel32.INSTANCE;

        // 打开进程以查询信息。PROCESS_QUERY_INFORMATION 和 PROCESS_VM_READ
        // 是所需的访问权限，以获取进程的可执行文件路径。
        WinNT.HANDLE process = kernel32.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false, pid);

        try {
            // 确保成功获取到进程句柄。
            if (process != null) {
                // 分配字符数组以存储路径。
                char[] path = new char[WinDef.MAX_PATH];
                // 初始化大小为路径的最大长度。
                int size = path.length;
                // 调用 QueryFullProcessImageName 获取进程的完整路径。
                boolean success = Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, path, new IntByReference(size));
                // 如果成功获取路径，返回路径字符串。
                if (success) {
                    return new String(path).trim();
                }
            }
        } finally {
            // 确保在操作完成后关闭进程句柄。
            if (process != null) {
                kernel32.CloseHandle(process);
            }
        }
        // 如果无法获取路径，返回 null。
        return null;
    }
}
