/// SOAR Client — Application entry point.
///
/// Wires together the three-tiered SOAR pipeline screens
/// with a bottom navigation bar and the app-wide dark theme.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme/soar_theme.dart';
import 'screens/dashboard_screen.dart';
import 'screens/alerts_screen.dart';
import 'screens/reporting_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  // Force dark status bar for a cohesive look.
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: SoarTheme.surface,
    systemNavigationBarIconBrightness: Brightness.light,
  ));

  runApp(const SoarApp());
}

class SoarApp extends StatelessWidget {
  const SoarApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SOAR Pipeline',
      debugShowCheckedModeBanner: false,
      theme: SoarTheme.build(),
      home: const SoarShell(),
    );
  }
}

/// Root shell with bottom navigation across the three tiers.
class SoarShell extends StatefulWidget {
  const SoarShell({super.key});

  @override
  State<SoarShell> createState() => _SoarShellState();
}

class _SoarShellState extends State<SoarShell> {
  int _currentIndex = 0;

  // Preserve screen state across tab switches.
  final List<Widget> _screens = const [
    DashboardScreen(),
    AlertsScreen(),
    ReportingScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _currentIndex, children: _screens),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          border: Border(
            top: BorderSide(color: SoarTheme.border, width: 1),
          ),
        ),
        child: BottomNavigationBar(
          currentIndex: _currentIndex,
          onTap: (i) => setState(() => _currentIndex = i),
          items: const [
            BottomNavigationBarItem(
              icon: Icon(Icons.shield_outlined),
              activeIcon: Icon(Icons.shield),
              label: 'Triage',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.bug_report_outlined),
              activeIcon: Icon(Icons.bug_report),
              label: 'Alerts',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.description_outlined),
              activeIcon: Icon(Icons.description),
              label: 'Reports',
            ),
          ],
        ),
      ),
    );
  }
}
