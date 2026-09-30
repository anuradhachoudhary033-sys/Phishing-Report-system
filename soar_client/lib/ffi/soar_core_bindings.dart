import 'dart:ffi';
import 'dart:io';
import 'package:ffi/ffi.dart';

// Define the Dart struct corresponding to the C struct
final class TriageResult extends Struct {
  @Double()
  external double riskScore;

  @Bool()
  external bool isPhishing;

  @Uint32()
  external int bloomMatchCount;
}

// Function typedefs
typedef InitBloomFilterC = Void Function(Size capacity);
typedef InitBloomFilterDart = void Function(int capacity);

typedef EvaluateUrlFastC = TriageResult Function(Pointer<Utf8> url);
typedef EvaluateUrlFastDart = TriageResult Function(Pointer<Utf8> url);

class SoarCoreBindings {
  static final DynamicLibrary _lib = Platform.isAndroid
      ? DynamicLibrary.open('libsoar_core.so')
      : DynamicLibrary.process();

  static final InitBloomFilterDart initBloomFilter = _lib
      .lookup<NativeFunction<InitBloomFilterC>>('init_bloom_filter')
      .asFunction();

  static final EvaluateUrlFastDart evaluateUrlFast = _lib
      .lookup<NativeFunction<EvaluateUrlFastC>>('evaluate_url_fast')
      .asFunction();
}
