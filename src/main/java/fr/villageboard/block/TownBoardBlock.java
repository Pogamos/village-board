package fr.villageboard.block;

import com.mojang.serialization.MapCodec;
import fr.villageboard.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Tableau de la mairie : le poser fonde un village, clic droit pour ouvrir le tableau. */
public class TownBoardBlock extends HorizontalDirectionalBlock {

	public static final MapCodec<TownBoardBlock> CODEC = simpleCodec(TownBoardBlock::new);
	private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(0, 0, 6, 16, 16, 10));

	public TownBoardBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		if (context.getLevel() instanceof ServerLevel level && VillageManager.get() != null
				&& !VillageManager.get().canFound(level, context.getClickedPos(), context.getPlayer())) {
			return null;
		}
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel serverLevel && VillageManager.get() != null) {
			VillageManager.get().found(serverLevel, pos, placer, stack);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer && VillageManager.get() != null) {
			VillageManager.get().openBoard(serverPlayer, pos);
		}
		return InteractionResult.SUCCESS;
	}
}
