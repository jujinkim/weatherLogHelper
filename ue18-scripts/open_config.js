var fso = new ActiveXObject("Scripting.FileSystemObject");
var configPath = UltraEdit.activeDocument.path + "wlh.config.json";
if (!fso.FileExists(configPath)) {
  UltraEdit.outputWindow.write("Missing wlh.config.json in current directory\n");
} else {
  UltraEdit.open(configPath);
}
