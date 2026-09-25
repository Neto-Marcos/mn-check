export function isExplicitlyCounted(item) {
  return item?.estado === "CONTADO" || item?.ultimaOcorrenciaId != null;
}

export function initialCountQuantity(item) {
  return isExplicitlyCounted(item) ? (item?.quantidadeContada ?? 0) : 1;
}

export function countActionType(item) {
  return isExplicitlyCounted(item) ? "CORRECAO" : "DEFINIR";
}

export function countDisplay(item) {
  return isExplicitlyCounted(item) ? `${item?.quantidadeContada ?? 0} un` : "Pendente";
}
