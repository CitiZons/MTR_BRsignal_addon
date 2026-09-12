package org.mtrbr;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.RegistryObject;
import org.mtrbr.block.SpeedSignBlock;
import org.mtrbr.block.SpeedSignBlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Decoration registrations, deliberately separate from signal/route registries. */
public final class SpeedSigns {
    public static final List<RegistryObject<SpeedSignBlock>> BLOCKS = new ArrayList<>();
    public static final List<RegistryObject<BlockItem>> ITEMS = new ArrayList<>();
    static {
        for (boolean warning : new boolean[]{false, true}) {
            String name = warning ? "advance_warning" : "permanent_speed";
            register(name + "_sign", warning, false, "");
            register(name + "_sign_double", warning, true, "");
            for (String arrow : new String[]{"left", "both", "right"}) {
                register(name + "_arrow_" + arrow, warning, false, arrow);
            }
        }
        register("text_sign", false, false, "", true);
    }
    public static final RegistryObject<BlockEntityType<SpeedSignBlockEntity>> ENTITY = MTRBR.BLOCK_ENTITIES.register("speed_sign",
            () -> BlockEntityType.Builder.of(SpeedSignBlockEntity::new, BLOCKS.stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));

    private SpeedSigns() {}
    public static void init() {}

    private static void register(String id, boolean warning, boolean doubleLine, String arrow) {
        register(id, warning, doubleLine, arrow, false);
    }
    private static void register(String id, boolean warning, boolean doubleLine, String arrow, boolean textSign) {
        var block = MTRBR.BLOCKS.register(id, () -> new SpeedSignBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion().dynamicShape(), warning, doubleLine, arrow, textSign));
        BLOCKS.add(block);
        ITEMS.add(MTRBR.ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties())));
    }
}
