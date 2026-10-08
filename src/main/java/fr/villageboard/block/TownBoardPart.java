package fr.villageboard.block;

import net.minecraft.util.StringRepresentable;

/** Position d'un bloc technique dans le tableau de mairie de deux blocs sur deux. */
public enum TownBoardPart implements StringRepresentable {
	LOWER_LEFT("lower_left"),
	LOWER_RIGHT("lower_right"),
	UPPER_LEFT("upper_left"),
	UPPER_RIGHT("upper_right");

	private final String name;

	TownBoardPart(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}
}
