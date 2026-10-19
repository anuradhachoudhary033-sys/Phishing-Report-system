import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/soar_models.dart';

class ApiService {
  /// Base URL of the FastAPI backend.
  final String baseUrl;

  ApiService({this.baseUrl = 'https://mangle-fading-deploy.ngrok-free.dev'});

  static const Map<String, String> _ngrokHeaders = {
    'ngrok-skip-browser-warning': '1',
  };

  /// Fetch list of generated intelligence reports.
  Future<List<IntelReport>> fetchReports() async {
    final response = await http.get(
      Uri.parse('$baseUrl/api/v1/reports'),
      headers: {
        'Accept': 'application/json',
        ..._ngrokHeaders,
      },
    );

    if (response.statusCode == 200) {
      final List<dynamic> data = jsonDecode(response.body) as List<dynamic>;
      return data
          .map((e) => IntelReport.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    throw Exception('Failed to fetch reports: ${response.statusCode}');
  }

  /// Submit a CERT-In intelligence package for law enforcement escalation.
  Future<bool> submitReport(String reportId) async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/reports/$reportId/submit'),
      headers: {
        'Content-Type': 'application/json',
        ..._ngrokHeaders,
      },
    );
    return response.statusCode == 200 || response.statusCode == 201;
  }

  /// Download a report PDF (returns raw bytes).
  Future<List<int>> downloadReportPdf(String reportId) async {
    final response = await http.get(
      Uri.parse('$baseUrl/api/v1/reports/$reportId/pdf'),
      headers: _ngrokHeaders,
    );
    if (response.statusCode == 200) {
      return response.bodyBytes;
    }
    throw Exception('Failed to download report: ${response.statusCode}');
  }
}