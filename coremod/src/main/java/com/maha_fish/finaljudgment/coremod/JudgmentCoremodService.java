package com.maha_fish.finaljudgment.coremod;

import com.maha_fish.finaljudgment.common.Constants;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;

import java.util.List;
import java.util.Set;

/**
 * Coremod 服务入口（5.2 功能 1）。
 *
 * <p>发现了三件事：① jar 名以 {@code -transformer} 结尾；② MANIFEST 声明
 * {@code FMLModType: GAMELIBRARY}；③ {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService}
 * 指向本类。缺任一项都静默不加载（规范 5）。</p>
 *
 * <p>{@code Constants.SYS_COREMOD_LOADED} 是编译期常量（javac 内联），运行期不依赖 common。</p>
 */
public final class JudgmentCoremodService implements ITransformationService {

    @Override
    public String name() {
        return "finaljudgment_coremod";
    }

    @Override
    public void initialize(IEnvironment environment) {
        CoremodLog.info("initialize: 服务已被 ModLauncher 发现");
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) throws IncompatibleEnvironmentException {
        System.setProperty(Constants.SYS_COREMOD_LOADED, "true");
        CoremodLog.info("onLoad: 就绪，已设置 " + Constants.SYS_COREMOD_LOADED
                + "=true（同批服务数=" + otherServices.size() + "）");
    }

    @Override
    public List<ITransformer> transformers() {
        System.setProperty("fj.coremod.registered", "true");
        CoremodLog.info("transformers: 注册 CoremodTransformer（5 个目标类）");
        return List.of(new CoremodTransformer());
    }
}
