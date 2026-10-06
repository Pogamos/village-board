package fr.villageboard.block;

import com.mojang.serialization.MapCodec;
import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Borne : posée près d'une mairie, elle devient un sommet du polygone qui délimite le village. */
public class BoundaryStoneBlock extends Block {

	public static final MapCodec<BoundaryStoneBlock> CODEC = simpleCodec(BoundaryStoneBlock::new);
	private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 15.5, 13);

	public BoundaryStoneBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel serverLevel && VillageManager.get() != null) {
			VillageManager.get().addBorne(serverLevel, pos, placer);
		}
	}
}
