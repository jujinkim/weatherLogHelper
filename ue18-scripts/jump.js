var fso = new ActiveXObject("Scripting.FileSystemObject");

if (UltraEdit.activeDocument.isModified && UltraEdit.activeDocument.readOnly) {
  UltraEdit.outputWindow.write("Active document is not readable for jump\n");
}

UltraEdit.activeDocument.selectLine();
var text = UltraEdit.activeDocument.selection;
var match = /L(\d+)/.exec(text);
if (!match) {
  UltraEdit.outputWindow.write("No line number found in selection\n");
} else {
  var line = parseInt(match[1], 10);
  var viewPath = UltraEdit.activeDocument.path + UltraEdit.activeDocument.name;
  var sourcePath = viewPath;
  if (viewPath.indexOf(".wlhview.txt") !== -1) {
    sourcePath = viewPath.replace(/\.wlhview\.txt$/, "");
  }
  if (!fso.FileExists(sourcePath)) {
    UltraEdit.outputWindow.write("Source file not found: " + sourcePath + "\n");
  } else {
    UltraEdit.open(sourcePath);
    UltraEdit.activeDocument.gotoLine(line);
  }
}
