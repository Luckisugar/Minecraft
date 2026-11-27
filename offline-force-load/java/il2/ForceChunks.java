package il2;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.TicketController;

public final class ForceChunks {
  private ForceChunks() {}

  public static boolean block(TicketController controller, ServerLevel level, BlockPos pos, int x, int z, boolean add, boolean ticking) {
    return controller.forceChunk(level, pos, x, z, add, ticking);
  }

  public static boolean uuid(TicketController controller, ServerLevel level, UUID id, int x, int z, boolean add, boolean ticking) {
    return controller.forceChunk(level, id, x, z, add, ticking);
  }
}
