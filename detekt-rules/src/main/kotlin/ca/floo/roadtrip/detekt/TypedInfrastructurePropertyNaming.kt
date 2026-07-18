package ca.floo.roadtrip.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

class TypedInfrastructurePropertyNaming(
    config: Config,
) : Rule(
    config,
    "Requires fields typed as *Repo, *Service, or *Client to include the same suffix in their property name.",
) {
    private val typeSuffixes: List<String> by config(defaultValue = listOf("Repo", "Service", "Client"))

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.parent !is KtClassBody) return
        validateFieldName(
            name = property.name,
            typeName = property.infrastructureTypeName(),
            entity = Entity.atName(property),
        )
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (!parameter.hasValOrVar()) return
        validateFieldName(
            name = parameter.name,
            typeName = parameter.typeReference?.text,
            entity = Entity.atName(parameter),
        )
    }

    private fun validateFieldName(
        name: String?,
        typeName: String?,
        entity: Entity,
    ) {
        val propertyName = name ?: return
        val shortTypeName = typeName?.shortTypeName() ?: return
        val expectedSuffix = requiredInfrastructureSuffix(shortTypeName, typeSuffixes) ?: return
        if (hasInfrastructureSuffix(propertyName, expectedSuffix)) return

        report(
            Finding(
                entity,
                "Field '$propertyName' has type '$shortTypeName'; rename it so the property name ends with '$expectedSuffix'.",
            ),
        )
    }
}

internal fun requiredInfrastructureSuffix(
    typeName: String,
    suffixes: List<String>,
): String? = suffixes.firstOrNull { suffix -> typeName.shortTypeName().endsWith(suffix) }

internal fun hasInfrastructureSuffix(
    propertyName: String,
    suffix: String,
): Boolean = propertyName.endsWith(suffix)

private fun String.shortTypeName(): String =
    trim()
        .removeSuffix("?")
        .substringBefore("<")
        .substringAfterLast(".")

private fun KtProperty.infrastructureTypeName(): String? =
    typeReference?.text
        ?: (initializer as? KtCallExpression)
            ?.calleeExpression
            ?.text
