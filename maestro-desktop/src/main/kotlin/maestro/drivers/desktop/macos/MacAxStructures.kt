package maestro.drivers.desktop.macos

import com.sun.jna.Structure

@Structure.FieldOrder("x", "y")
class MacAxCGPoint : Structure() {
    @JvmField var x: Double = 0.0
    @JvmField var y: Double = 0.0
}

@Structure.FieldOrder("width", "height")
class MacAxCGSize : Structure() {
    @JvmField var width: Double = 0.0
    @JvmField var height: Double = 0.0
}
