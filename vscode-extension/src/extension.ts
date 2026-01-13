import * as vscode from 'vscode';
import { execFile } from 'child_process';

const output = vscode.window.createOutputChannel('WLH');

function runWlh(args: string[]): Promise<string> {
  const config = vscode.workspace.getConfiguration('wlh');
  const baseUrl = config.get<string>('update.baseUrl');
  const finalArgs = [...args];
  if (baseUrl && baseUrl.length > 0) {
    finalArgs.unshift(baseUrl);
    finalArgs.unshift('--base-url');
  }

  return new Promise((resolve, reject) => {
    execFile('wlh', finalArgs, { maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
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

async function scanFastThenFull(filePath: string) {
  output.appendLine(`Scanning ${filePath}`);
  try {
    const result = await runWlh(['scan', '--mode', 'fast_then_full', filePath]);
    output.appendLine(result);
  } catch (err) {
    output.appendLine(`WLH error: ${(err as Error).message}`);
  }
}

export function activate(context: vscode.ExtensionContext) {
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

export function deactivate() {
  output.dispose();
}
