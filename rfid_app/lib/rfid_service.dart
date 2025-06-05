import 'package:flutter/services.dart';

class RfidService {
  static const MethodChannel _channel = MethodChannel('rfid_channel');

  static Future<String?> startScan() async {
    try {
      final result = await _channel.invokeMethod<String>('startScan');
      return result;
    } catch (e) {
      print('Error calling startScan: $e');
      return null;
    }
  }

  static Future<void> stopScan() async {
    try {
      await _channel.invokeMethod('stopScan');
    } catch (e) {
      print('Error calling stopScan: $e');
    }
  }
}
