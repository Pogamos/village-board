package fr.villageboard.block;

import com.mojang.serialization.MapCodec;
import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Borne : posée près d'une mairie, elle devient un sommet du polygone qui délimite le village. */
public class BoundaryStoneBlock extends Block {

	public static final MapCodec<BoundaryStoneBlock> CODEC = simpleCodec(BoundaryStoneBlock::new);
	public static final EnumProperty<DoubleBlockHalf> HALF = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
	private static final VoxelShape LOWER_SHAPE = Block.box(4, 0, 4, 12, 16, 12);
	private static final VoxelShape UPPER_SHAPE = Block.box(4, 0, 4, 12, 8, 12);

	public BoundaryStoneBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER_SHAPE : UPPER_SHAPE;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HALF);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos upper = context.getClickedPos().above();
		Level level = context.getLevel();
		if (level.isOutsideBuildHeight(upper) || !level.getWorldBorder().isWithinBounds(upper)
				|| !level.getBlockState(upper).canBeReplaced(context)) {
			return null;
		}
		return defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		level.setBlockAndUpdate(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER));
		if (level instanceof ServerLevel serverLevel && VillageManager.get() != null) {
			VillageManager.get().addBorne(serverLevel, pos, placer);
		}
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickAccess, BlockPos pos,
			Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		DoubleBlockHalf half = state.getValue(HALF);
		Direction otherDirection = half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN;
		if (direction == otherDirection && (!neighborState.is(this) || neighborState.getValue(HALF) == half)) {
			return Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, tickAccess, pos, direction, neighborPos, neighborState, random);
	}

	/** Bloc inférieur qui porte la position enregistrée de la borne. */
	public static BlockPos getAnchorPos(BlockPos pos, BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
	}

	/** Ajoute la moitié technique supérieure aux bornes posées par une ancienne version. */
	public static boolean ensureMultiblock(Level level, BlockPos anchor) {
		BlockState anchorState = level.getBlockState(anchor);
		if (!anchorState.is(ModBlocks.BOUNDARY_STONE)) {
			return false;
		}
		BlockPos upper = anchor.above();
		BlockState upperState = level.getBlockState(upper);
		if (!upperState.is(ModBlocks.BOUNDARY_STONE) && !upperState.canBeReplaced()) {
			return false;
		}
		BlockState lower = anchorState.setValue(HALF, DoubleBlockHalf.LOWER);
		level.setBlock(anchor, lower, Block.UPDATE_CLIENTS);
		level.setBlockAndUpdate(upper, lower.setValue(HALF, DoubleBlockHalf.UPPER));
		return true;
	}
}
