package fr.villageboard.item;

/** Ce que lie un contrat : un poste de travail (contrat de travail) ou un lit (bail de logement). */
public enum ContractKind {
	WORK("villageboard.contract."),
	HOME("villageboard.lease.");

	/** Préfixe des clés de traduction propres à ce type de contrat. */
	public final String keyPrefix;

	ContractKind(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}
}
