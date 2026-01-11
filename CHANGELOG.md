# Changelog

All notable changes to JBonjourBrowser are documented in this file.

## [2.0.0] - 2026-01-11

### Added

- **Help Menu**: Complete Help menu with documentation, update check, and about dialog
- **Online Documentation**: Opens GitHub README in default browser
- **Update Check**: Checks GitHub releases API for newer versions
- **About Dialog**: Shows version, authors, clickable links (original project, GitHub repo, license), and system info
- **AppVeyor CI**: Continuous integration build configuration
- **Copy to Clipboard**: Copy selected node text using Ctrl+C or right-click context menu
- **Alphabetical Sorting**: All tree nodes (domains, service types, services, TXT records) are now sorted case-insensitively
- **FlatLaf Look & Feel**: Modern cross-platform UI appearance with `flatlaf-3.4.jar`
- **Debug Logging**: Optional `-debug` command line flag for verbose logging
- **Java Environment Info**: Displays Java version and encoding at startup

### Changed

- **Java Version**: Now requires Java 21+ (Java 25 recommended for best UTF-8 support)
- **UTF-8 Encoding**: Full UTF-8 support for service names and TXT records
  - Build configured with `-Dfile.encoding=UTF-8`
  - Runtime uses UTF-8 for stdout/stderr encoding
  - Proper handling of international characters (French, German, etc.)
- **Binary TXT Record Display**: Binary data now shown as `[HEX BYTES] "ASCII"` format
  - Example: `[6A 8D 80 50] "j..P"` where non-printable bytes shown as dots
  - Strict UTF-8 validation using `CharsetDecoder` with error reporting

### Fixed

- **Service Removal**: Fixed batch update scheduling issue where services weren't being removed from the UI
  - Added 500ms fallback delay for MORE_COMING flag operations
- **Duplicate Filtering**: Services announced on multiple network interfaces are now deduplicated
- **TXT Record Validation**: Invalid UTF-8 byte sequences no longer produce garbage characters

### Performance

- **Batch Updates**: DNSSD callbacks are batched using the MORE_COMING flag to reduce UI updates
- **Concurrent Collections**: Thread-safe data structures for multi-threaded DNSSD callbacks

### Technical Changes

- Updated `build-impl.xml` to use Java 25 for compilation and runtime
- Updated `project.properties` to target Java 21
- Updated IntelliJ IDEA project configuration for Java 21 and FlatLaf
- Added `--enable-native-access=ALL-UNNAMED` JVM argument for FlatLaf native library
- Fixed generics compatibility: `Enumeration<? extends TreeNode>` for Java 21+ compatibility

---

## [1.0.0] - 2006

### Initial Release

- Original implementation by Denis Abramov and Myounghwan Lee
- Internet Real Time Lab, Columbia University
- Basic Bonjour/Zeroconf service browser functionality
- Tree visualization of domains, service types, and instances
- Service resolution with hostname, port, and TXT records

---

## Known Issues

| Issue | Status | Workaround |
|-------|--------|------------|
| Tamil/Sinhala/CJK scripts display as boxes in UI | Open | Data is correct (UTF-8), but default Swing fonts lack glyphs. Consider using "Nirmala UI" font on Windows. |
