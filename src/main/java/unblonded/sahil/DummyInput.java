package unblonded.sahil;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;

public class DummyInput extends Input {
    public DummyInput() {
        super();
    }

    public void setMovementVector(Vec2f vector) {
        this.movementVector = vector;
    }
}