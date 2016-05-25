package epic.logo

class LossAugmentedMaxMarginDecoder[T, W](val oracleInferencer : OracleInferencer[T, _, W], val argmaxer : LossAugmentedArgmaxInferencer[T, _, W]) extends Decoder[T, W] {

  def decode(weights : Weights[W], instance : T) : (FeatureVector[W], Double) = {
    val (y_*, f_*, l_*) = oracleInferencer.oracle(weights, instance)
    val (y, f, l) = argmaxer.lossAugmentedArgmax(weights, instance, 1.0, 1.0)
    (f_* - f, l - l_*)
  }

}