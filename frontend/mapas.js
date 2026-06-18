import { MAP_FILE_TYPES } from "./state.js";

export function mapContentType(file) {
  if (MAP_FILE_TYPES.has(file.type)) return file.type;
  const extension = String(file.name || "").toLowerCase().split(".").pop();
  return {
    pdf: "application/pdf",
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    webp: "image/webp",
    heic: "image/heic",
    heif: "image/heif",
  }[extension] || "";
}

export function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error("Não foi possível ler o arquivo."));
    reader.readAsDataURL(file);
  });
}
