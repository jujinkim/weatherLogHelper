"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.activate = activate;
exports.deactivate = deactivate;
const vscode = __importStar(require("vscode"));
const child_process_1 = require("child_process");
const output = vscode.window.createOutputChannel('WLH');
function runWlh(args) {
    const config = vscode.workspace.getConfiguration('wlh');
    const baseUrl = config.get('update.baseUrl');
    const finalArgs = [...args];
    if (baseUrl && baseUrl.length > 0) {
        finalArgs.unshift(baseUrl);
        finalArgs.unshift('--base-url');
    }
    return new Promise((resolve, reject) => {
        (0, child_process_1.execFile)('wlh', finalArgs, { maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
            if (stderr) {
                output.appendLine(stderr.trim());
            }
            if (err) {
                reject(err);
                return;
            }
            resolve(stdout.trim());
        });
    });
}
async function scanFastThenFull(filePath) {
    output.appendLine(`Scanning ${filePath}`);
    try {
        const result = await runWlh(['scan', '--mode', 'fast_then_full', filePath]);
        output.appendLine(result);
    }
    catch (err) {
        output.appendLine(`WLH error: ${err.message}`);
    }
}
function activate(context) {
    const scanCommand = vscode.commands.registerCommand('wlh.scanFastThenFull', async () => {
        const editor = vscode.window.activeTextEditor;
        if (!editor) {
            vscode.window.showInformationMessage('WLH: No active file');
            return;
        }
        await scanFastThenFull(editor.document.fileName);
    });
    const openListener = vscode.workspace.onDidOpenTextDocument(async (doc) => {
        if (doc.isUntitled) {
            return;
        }
        const lower = doc.fileName.toLowerCase();
        if (lower.endsWith('.log') || lower.endsWith('.txt')) {
            await scanFastThenFull(doc.fileName);
        }
    });
    context.subscriptions.push(scanCommand, openListener, output);
}
function deactivate() {
    output.dispose();
}
//# sourceMappingURL=extension.js.map