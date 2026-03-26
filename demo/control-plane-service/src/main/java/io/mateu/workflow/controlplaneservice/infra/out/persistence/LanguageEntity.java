package io.mateu.workflow.controlplaneservice.infra.out.persistence;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@AllArgsConstructor@NoArgsConstructor
@Getter
public class LanguageEntity {

@Id
String code;

String name;

}
