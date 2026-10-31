import fs from 'fs';
import path from 'path';

// This script unpacks hostinger-code.json into the reference-site folder
const downloadsDir = 'C:\\Users\\Administrator\\Downloads';
const targetDir = 'E:\\shop-b2b2c\\reference-site\\extracted';

function findLatestExportFile() {
  // Check if file is in Downloads or in current directory
  const candidates = [
    path.join(downloadsDir, 'hostinger-code.json'),
    path.join(downloadsDir, 'project-files.json'),
    path.join('E:\\shop-b2b2c\\reference-site', 'hostinger-code.json')
  ];

  for (const c of candidates) {
    if (fs.existsSync(c)) {
      return c;
    }
  }

  // Check Downloads directory for any json created in the last 15 minutes
  if (fs.existsSync(downloadsDir)) {
    const files = fs.readdirSync(downloadsDir);
    for (const file of files) {
      if (file.toLowerCase().includes('hostinger') || file.toLowerCase().includes('project') || file.toLowerCase().includes('code')) {
        if (file.endsWith('.json')) {
          return path.join(downloadsDir, file);
        }
      }
    }
  }

  return null;
}

function unpack() {
  const jsonPath = findLatestExportFile();
  if (!jsonPath) {
    console.error('No export JSON file found yet in Downloads or reference-site.');
    process.exit(1);
  }

  console.log('Found exported file:', jsonPath);
  const raw = fs.readFileSync(jsonPath, 'utf8');
  let data;
  try {
    data = JSON.parse(raw);
  } catch (e) {
    console.error('Failed to parse JSON:', e.message);
    process.exit(1);
  }

  // Handle both array format [{path, content}] and object format {path: content}
  let fileList = [];
  if (Array.isArray(data)) {
    fileList = data;
  } else if (typeof data === 'object') {
    for (const [filePath, fileContent] of Object.entries(data)) {
      if (typeof fileContent === 'string') {
        fileList.push({ path: filePath, content: fileContent });
      } else if (fileContent && typeof fileContent === 'object' && fileContent.content) {
        fileList.push({ path: filePath, content: fileContent.content });
      }
    }
  }

  console.log(`Unpacking ${fileList.length} files into ${targetDir}...`);

  let count = 0;
  for (const item of fileList) {
    if (!item.path) continue;
    // Clean path
    let relPath = item.path.replace(/^\/+/, '').replace(/^apps\/web\//, 'apps/web/');
    const fullPath = path.join(targetDir, relPath);
    const parentDir = path.dirname(fullPath);

    fs.mkdirSync(parentDir, { recursive: true });
    fs.writeFileSync(fullPath, item.content || '', 'utf8');
    count++;
    console.log(`  [+] ${relPath} (${(item.content || '').length} bytes)`);
  }

  console.log(`\nSuccessfully unpacked ${count} files to ${targetDir}!`);
}

unpack();
