class CameraBrightness {
  final int brightness;
  final int minBrightness;
  final int maxBrightness;

  CameraBrightness({
    required this.brightness,
    required this.minBrightness,
    required this.maxBrightness,
  });

  factory CameraBrightness.fromJson(Map<String, dynamic> json) {
    return CameraBrightness(
      brightness: json['value'] as int,
      minBrightness: json['min'] as int,
      maxBrightness: json['max'] as int,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'value': brightness,
      'min': minBrightness,
      'max': maxBrightness,
    };
  }
}
