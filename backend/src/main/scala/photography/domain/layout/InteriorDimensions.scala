package photography.domain.layout

final case class PrintFrame(x: Double, y: Double, width: Double, height: Double)

object InteriorDimensions:
  val width: Double  = 2381.102
  val height: Double = 765.354
  val bleed: Double  = 3.5

  def contentFrame(box: SlotBox): PrintFrame =
    PrintFrame(box.x * width / 100.0, box.y * height / 100.0, box.width * width / 100.0, box.height * height / 100.0)

  def photoFrame(box: SlotBox, edges: Set[SpreadEdge]): PrintFrame =
    val frame  = contentFrame(box)
    val left   = if edges.contains(SpreadEdge.Left) then bleed else 0.0
    val right  = if edges.contains(SpreadEdge.Right) then bleed else 0.0
    val top    = if edges.contains(SpreadEdge.Top) then bleed else 0.0
    val bottom = if edges.contains(SpreadEdge.Bottom) then bleed else 0.0
    PrintFrame(frame.x - left, frame.y - top, frame.width + left + right, frame.height + top + bottom)
