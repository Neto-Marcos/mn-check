export function conferenceStatusLabel(value) {
  return {
    EM_ANDAMENTO: "Conferência em andamento",
    PAUSADA: "Conferência pausada",
    FINALIZADA: "Conferência finalizada",
    CANCELADA: "Conferência cancelada"
  }[value] || "Conferência";
}
