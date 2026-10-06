package fr.villageboard.item;

/**
 * Ce que lie un contrat : un poste de travail (contrat de travail), un lit (bail de logement)
 * ou deux villageois (acte de mariage).
 */
public enum ContractKind {
	WORK("villageboard.contract."),
	HOME("villageboard.lease."),
	MARRIAGE("villageboard.marriage.");

	/** Préfixe des clés de traduction propres à ce type de contrat. */
	public final String keyPrefix;

	ContractKind(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}
}
