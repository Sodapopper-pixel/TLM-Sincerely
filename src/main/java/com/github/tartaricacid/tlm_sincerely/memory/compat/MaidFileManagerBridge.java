package com.github.tartaricacid.tlm_sincerely.memory.compat;

import com.github.tartaricacid.tlm_sincerely.memory.MaidMemoryManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * MaidFileManager（{@code maid_file_manager}）联动桥：把简易记忆系统接入其
 * .maid 女仆档案的导出/导入流程。
 *
 * <p>记忆以 JSON 文件形式存于 config 目录、按女仆 UUID 关联，不在女仆实体 NBT 内，
 * 对方按 {@code saveWithoutId} 的实体导出带不走；其迁移 SPI
 * （{@code io.github.zgxhzhr.maidfm.spi.MaidMigrationProvider}）专为这类
 * "UUID 关联的外部数据"设计，且 {@code importData} 在导入女仆入世界、UUID 已确定后
 * 回调，正好完成记忆到新 UUID 的换绑（自动工作绑定快照存在实体 NBT 内，无需本桥）。
 *
 * <p>对方未发布 maven 仓库，为保持零编译依赖，运行时检测其已加载后用反射取出
 * SPI 接口，以 {@link Proxy} 实现并注册；回调参数全部落在本模组的强依赖类型上
 * （EntityMaid / CompoundTag / ResourceLocation），回调体内无需再反射。
 *
 * <p>extras 载荷（id {@code tlm_sincerely:maid_memory}）：{@code version}=1、
 * {@code memory_json}=记忆文件 JSON 原文。JSON 结构与 MC 版本无关，
 * 1.20.1forge 线与 1.21.1neo 线通用，可随档案跨版本迁移。
 */
public final class MaidFileManagerBridge implements InvocationHandler {
    private static final Logger LOGGER = LogManager.getLogger("TLM_Sincerely/MaidFileBridge");

    private static final String TARGET_MOD_ID = "maid_file_manager";
    private static final String SPI_REGISTRY_CLASS = "io.github.zgxhzhr.maidfm.spi.MaidMigrationRegistry";
    private static final String SPI_INTERFACE_CLASS = "io.github.zgxhzhr.maidfm.spi.MaidMigrationProvider";

    private static final String EXTRA_KEY_VERSION = "version";
    private static final String EXTRA_KEY_MEMORY_JSON = "memory_json";
    private static final int EXTRA_VERSION = 1;

    private static final ResourceLocation PROVIDER_ID =
            ResourceLocation.fromNamespaceAndPath("tlm_sincerely", "maid_memory");

    private static boolean registered;

    private MaidFileManagerBridge() {
    }

    /** 在 common setup 调用；未安装对方模组、或对方版本不含 SPI 时静默跳过。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        if (!ModList.get().isLoaded(TARGET_MOD_ID)) {
            return;
        }
        try {
            Class<?> registryClass = Class.forName(SPI_REGISTRY_CLASS);
            Class<?> providerClass = Class.forName(SPI_INTERFACE_CLASS);
            Object provider = Proxy.newProxyInstance(providerClass.getClassLoader(),
                    new Class<?>[]{providerClass}, new MaidFileManagerBridge());
            registryClass.getMethod("register", providerClass).invoke(null, provider);
            LOGGER.info("Registered maid memory migration provider to {}", TARGET_MOD_ID);
        } catch (ClassNotFoundException e) {
            // 装了 1.4.0 之前的不含 SPI 的旧版本：可用但无联动，不算错误
            LOGGER.info("{} present without migration SPI ({}), memory migration unavailable",
                    TARGET_MOD_ID, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Failed to register migration provider to {}", TARGET_MOD_ID, e);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // Proxy 不会自动执行接口 default 方法（isAvailable）与 Object 方法，统一在此分发
        return switch (method.getName()) {
            case "getId" -> PROVIDER_ID;
            case "getDependencyModId" -> "tlm_sincerely";
            case "isAvailable" -> true;
            case "export" -> export(args);
            case "importData" -> {
                importData(args);
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "TLM-Sincerely maid memory migration provider";
            default -> {
                LOGGER.debug("Unhandled SPI method {}, returning null", method.getName());
                yield null;
            }
        };
    }

    private static Object export(Object[] args) {
        if (args == null || args.length < 1 || !(args[0] instanceof EntityMaid maid)) {
            return null;
        }
        String json = MaidMemoryManager.exportMemoryJson(maid.getUUID());
        if (json == null) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt(EXTRA_KEY_VERSION, EXTRA_VERSION);
        tag.putString(EXTRA_KEY_MEMORY_JSON, json);
        return tag;
    }

    private static void importData(Object[] args) {
        if (args == null || args.length < 2
                || !(args[0] instanceof EntityMaid maid) || !(args[1] instanceof CompoundTag data)) {
            return;
        }
        if (!data.contains(EXTRA_KEY_MEMORY_JSON)) {
            // 老档案或导出时本就无记忆
            return;
        }
        if (!MaidMemoryManager.importMemoryJson(maid.getUUID(), data.getString(EXTRA_KEY_MEMORY_JSON))) {
            LOGGER.warn("Failed to import maid memory for imported maid {}", maid.getUUID());
        }
    }
}
