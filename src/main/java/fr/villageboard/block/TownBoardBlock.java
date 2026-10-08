package fr.villageboard.block;

import com.mojang.serialization.MapCodec;
import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Tableau de la mairie : le poser fonde un village, clic droit pour ouvrir le tableau. */
public class TownBoardBlock extends HorizontalDirectionalBlock {

	public static final MapCodec<TownBoardBlock> CODEC = simpleCodec(TownBoardBlock::new);
	public static final EnumProperty<TownBoardPart> PART = EnumProperty.create("part", TownBoardPart.class);
	private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(0, 0, 0.8, 16, 16, 11));

	public TownBoardBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(PART, TownBoardPart.LOWER_LEFT));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PART);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos lowerLeft = context.getClickedPos();
		Direction facing = context.getHorizontalDirection().getOpposite();
		Direction right = rightOf(facing);
		BlockPos lowerRight = lowerLeft.relative(right);
		BlockPos upperLeft = lowerLeft.above();
		BlockPos upperRight = lowerRight.above();
		if (!canReplace(level, lowerRight, context) || !canReplace(level, upperLeft, context)
				|| !canReplace(level, upperRight, context)) {
			return null;
		}
		if (level instanceof ServerLevel serverLevel && VillageManager.get() != null
				&& !VillageManager.get().canFound(serverLevel, lowerLeft, context.getPlayer())) {
			return null;
		}
		return defaultBlockState().setValue(FACING, facing).setValue(PART, TownBoardPart.LOWER_LEFT);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		Direction right = rightOf(state.getValue(FACING));
		BlockPos lowerRight = pos.relative(right);
		level.setBlock(lowerRight, state.setValue(PART, TownBoardPart.LOWER_RIGHT), Block.UPDATE_CLIENTS);
		level.setBlock(pos.above(), state.setValue(PART, TownBoardPart.UPPER_LEFT), Block.UPDATE_CLIENTS);
		level.setBlock(lowerRight.above(), state.setValue(PART, TownBoardPart.UPPER_RIGHT), Block.UPDATE_ALL);
		if (level instanceof ServerLevel serverLevel && VillageManager.get() != null) {
			VillageManager.get().found(serverLevel, pos, placer, stack);
		}
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickAccess, BlockPos pos,
			Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		TownBoardPart connectedPart = connectedPart(state.getValue(PART), state.getValue(FACING), direction);
		if (connectedPart != null && (!neighborState.is(this)
				|| neighborState.getValue(FACING) != state.getValue(FACING)
				|| neighborState.getValue(PART) != connectedPart)) {
			return Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, tickAccess, pos, direction, neighborPos, neighborState, random);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer && VillageManager.get() != null) {
			VillageManager.get().openBoard(serverPlayer, getAnchorPos(pos, state));
		}
		return InteractionResult.SUCCESS;
	}

	/** Bloc inférieur gauche qui porte l'identité du tableau. */
	public static BlockPos getAnchorPos(BlockPos pos, BlockState state) {
		Direction left = rightOf(state.getValue(FACING)).getOpposite();
		return switch (state.getValue(PART)) {
			case LOWER_LEFT -> pos;
			case LOWER_RIGHT -> pos.relative(left);
			case UPPER_LEFT -> pos.below();
			case UPPER_RIGHT -> pos.below().relative(left);
		};
	}

	/** Complète les anciens tableaux à un seul bloc lors du chargement d'un monde. */
	public static boolean ensureMultiblock(Level level, BlockPos anchor) {
		BlockState anchorState = level.getBlockState(anchor);
		if (!anchorState.is(ModBlocks.TOWN_BOARD)) {
			return false;
		}
		Direction facing = anchorState.getValue(FACING);
		Direction right = rightOf(facing);
		BlockState base = anchorState.setValue(PART, TownBoardPart.LOWER_LEFT);
		BlockPos lowerRight = anchor.relative(right);
		BlockPos upperLeft = anchor.above();
		BlockPos upperRight = lowerRight.above();
		if (!canFill(level, lowerRight) || !canFill(level, upperLeft) || !canFill(level, upperRight)) {
			return false;
		}
		level.setBlock(anchor, base, Block.UPDATE_CLIENTS);
		level.setBlock(lowerRight, base.setValue(PART, TownBoardPart.LOWER_RIGHT), Block.UPDATE_CLIENTS);
		level.setBlock(upperLeft, base.setValue(PART, TownBoardPart.UPPER_LEFT), Block.UPDATE_CLIENTS);
		level.setBlock(upperRight, base.setValue(PART, TownBoardPart.UPPER_RIGHT), Block.UPDATE_ALL);
		return true;
	}

	private static boolean canReplace(Level level, BlockPos pos, BlockPlaceContext context) {
		return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
				&& level.getBlockState(pos).canBeReplaced(context);
	}

	private static boolean canFill(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(ModBlocks.TOWN_BOARD) || state.canBeReplaced();
	}

	private static TownBoardPart connectedPart(TownBoardPart part, Direction facing, Direction direction) {
		Direction right = rightOf(facing);
		Direction left = right.getOpposite();
		return switch (part) {
			case LOWER_LEFT -> direction == right ? TownBoardPart.LOWER_RIGHT
					: direction == Direction.UP ? TownBoardPart.UPPER_LEFT : null;
			case LOWER_RIGHT -> direction == left ? TownBoardPart.LOWER_LEFT
					: direction == Direction.UP ? TownBoardPart.UPPER_RIGHT : null;
			case UPPER_LEFT -> direction == right ? TownBoardPart.UPPER_RIGHT
					: direction == Direction.DOWN ? TownBoardPart.LOWER_LEFT : null;
			case UPPER_RIGHT -> direction == left ? TownBoardPart.UPPER_LEFT
					: direction == Direction.DOWN ? TownBoardPart.LOWER_RIGHT : null;
		};
	}

	private static Direction rightOf(Direction facing) {
		return facing.getCounterClockWise();
	}
}
